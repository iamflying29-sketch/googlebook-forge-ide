// forge-sehhealth: stand-ins for Samsung-only services that One UI needs and the emulated machine lacks (part of
// Forge IDE): vendor.samsung.hardware.health.ISehHealth, and the empty services listed in kEmptyServices below.
//
// Samsung's One UI starts its battery service with two hardware services: Android's standard health service and a
// Samsung-only one, ISehHealth, which only Samsung's own vendor partition provides. On the emulated ARM device the
// vendor partition is LineageOS's, so the Samsung one is missing and One UI's system process dies before the first
// screen. Makers usually hang their own interface off the standard service as a "binder extension", so this program
// registers a forwarding copy of the standard health service (every call goes through to the real one unchanged)
// that carries a Samsung-named extension, and also registers that extension under Samsung's name. The extension
// answers every call with a Samsung health record built from the standard service's HealthInfo, keeps the callback
// Samsung's battery service registers, and sends that callback the same record every 20 seconds, which is how
// Samsung's battery service learns the battery level.
#include <android/binder_ibinder.h>
#include <android/binder_parcel.h>
#include <android/binder_status.h>
#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>
#include <unistd.h>

// Functions of libbinder_ndk that Android's public NDK does not list (the platform's own tools use them): looked up
// at run time, where the device's library has them.
typedef binder_status_t (*add_service_fn)(AIBinder*, const char*);
typedef AIBinder* (*get_service_fn)(const char*);
typedef void (*join_pool_fn)(void);
typedef binder_status_t (*set_extension_fn)(AIBinder*, AIBinder*);

static const char* kTag = "forge-sehhealth";
static const char* kSehDescriptor = "vendor.samsung.hardware.health.ISehHealth";
static const char* kSehName = "vendor.samsung.hardware.health.ISehHealth/default";
static const char* kHealthDescriptor = "android.hardware.health.IHealth";
static const char* kHealthName = "android.hardware.health.IHealth/default";
static const char* kCallbackDescriptor = "vendor.samsung.hardware.health.ISehHealthInfoCallback";
// Other Samsung-only services One UI's system process waits for without end when they are missing. Each gets a
// stand-in that answers every call with "OK" and no data, which Java's proxies read as zero, null or empty.
static const char* kEmptyServices[][2] = {
    {"vendor.samsung.hardware.displayquality.ISehDisplayQuality", "vendor.samsung.hardware.displayquality.ISehDisplayQuality/default"},
};
static AIBinder* gReal = nullptr;
static AIBinder* gCallback = nullptr;
static AIBinder_Class* gCallbackClass = nullptr;
static pthread_mutex_t gLock = PTHREAD_MUTEX_INITIALIZER;

static void* onCreate(void*) { return nullptr; }
static void onDestroy(void*) {}

// The standard service's current HealthInfo, as the bytes of its getHealthInfo reply (transaction 10 of IHealth).
// When the standard service has no answer (the virtual machine's health service fails on some machines), a
// built-in record stands in: charger connected, battery present, full, 100 percent, good health. Java's parser
// stops at the record's length, so the fields after batteryTechnology are left out and read as their defaults.
static AParcel* fetchHealthInfo(int32_t* start) {
    AParcel* req = nullptr;
    AParcel* reply = nullptr;
    binder_status_t st = AIBinder_prepareTransaction(gReal, &req);
    if (st == STATUS_OK) st = AIBinder_transact(gReal, 10, &req, &reply, 0);
    AStatus* status = nullptr;
    int32_t exception = 0;
    if (st == STATUS_OK && reply && AParcel_readStatusHeader(reply, &status) == STATUS_OK) {
        exception = AStatus_getExceptionCode(status);
        AStatus_delete(status);
        *start = AParcel_getDataPosition(reply);
        int32_t size = AParcel_getDataSize(reply) - *start;
        if (exception == EX_NONE && size > 8) {
            __android_log_print(ANDROID_LOG_INFO, kTag, "standard HealthInfo: %d bytes", (int)size);
            return reply;
        }
    }
    if (reply) AParcel_delete(reply);
    __android_log_print(ANDROID_LOG_WARN, kTag, "the standard health service gave no HealthInfo (status %d, exception %d); using the built-in one", (int)st, (int)exception);
    AParcel* own = nullptr;
    if (AIBinder_prepareTransaction(gReal, &own) != STATUS_OK || !own) return nullptr;
    *start = AParcel_getDataPosition(own);
    AParcel_writeInt32(own, 1);                       // non-null
    int32_t lenPos = AParcel_getDataPosition(own);
    AParcel_writeInt32(own, 0);                       // length, patched below
    const int32_t ints[] = {1, 0, 0, 0,               // chargerAcOnline, chargerUsbOnline, chargerWirelessOnline, chargerDockOnline
                            0, 0,                     // maxChargingCurrentMicroamps, maxChargingVoltageMicrovolts
                            5, 2,                     // batteryStatus FULL, batteryHealth GOOD
                            1, 100, 4200, 250, 0, 0,  // present, level, millivolts, tenths of a degree, microamps, cycles
                            4000000, 4000000};        // batteryFullChargeUah, batteryChargeCounterUah
    for (int32_t v : ints) AParcel_writeInt32(own, v);
    AParcel_writeString(own, "Li-ion", 6);            // batteryTechnology
    int32_t end = AParcel_getDataPosition(own);
    AParcel_setDataPosition(own, lenPos);
    AParcel_writeInt32(own, end - lenPos);
    AParcel_setDataPosition(own, end);
    return own;
}

// Samsung's battery service learns the battery state from the callback it registers with Samsung's health service,
// so the registered callback is sent a Samsung health record (the standard HealthInfo as its first field) right
// away and every 20 seconds, as a one-way call.
static void writeSehInfo(AParcel* out) {
    int32_t start = 0;
    AParcel* info = fetchHealthInfo(&start);
    int32_t size = info ? AParcel_getDataSize(info) - start : 0;
    AParcel_writeInt32(out, 1);                 // non-null
    AParcel_writeInt32(out, 4 + size);          // length of the record including this field
    if (info) {
        AParcel_appendFrom(info, out, start, size);
        AParcel_delete(info);
    }
}

static void* deliverLoop(void*) {
    for (;;) {
        pthread_mutex_lock(&gLock);
        AIBinder* cb = gCallback;
        if (cb) AIBinder_incStrong(cb);
        pthread_mutex_unlock(&gLock);
        if (cb) {
            AParcel* req = nullptr;
            AParcel* reply = nullptr;
            if (AIBinder_prepareTransaction(cb, &req) == STATUS_OK) {
                writeSehInfo(req);
                binder_status_t st = AIBinder_transact(cb, 1, &req, &reply, FLAG_ONEWAY);
                __android_log_print(ANDROID_LOG_INFO, kTag, "delivered a Samsung health record to the callback: status %d", (int)st);
                if (reply) AParcel_delete(reply);
            }
            AIBinder_decStrong(cb);
        }
        sleep(20);
    }
    return nullptr;
}

// The Samsung interface: an OK status, then a result that reads as a Samsung health record whose first field is
// Android's standard HealthInfo (taken live from the standard service) and whose other fields are absent, so they
// read as their defaults. Methods that return nothing ignore the extra data.
static binder_status_t sehTransact(AIBinder*, transaction_code_t code, const AParcel* in, AParcel* out) {
    AStatus* ok = AStatus_newOk();
    binder_status_t st = AParcel_writeStatusHeader(out, ok);
    AStatus_delete(ok);
    if (st != STATUS_OK) return st;
    if (code == 0x00ffffff) return AParcel_writeInt32(out, 1);
    if (code == 0x00fffffe) return AParcel_writeString(out, "notfrozen", 9);
    if (code == 1) {   // registerCallback(ISehHealthInfoCallback): keep the callback and start delivering to it
        AIBinder* cb = nullptr;
        if (AParcel_readStrongBinder(in, &cb) == STATUS_OK && cb) {
            AIBinder_associateClass(cb, gCallbackClass);
            pthread_mutex_lock(&gLock);
            if (gCallback) AIBinder_decStrong(gCallback);
            gCallback = cb;
            pthread_mutex_unlock(&gLock);
            __android_log_print(ANDROID_LOG_INFO, kTag, "Samsung health callback registered");
        }
    }
    __android_log_print(ANDROID_LOG_INFO, kTag, "Samsung health call %u answered with a standard HealthInfo record", code);
    writeSehInfo(out);
    return STATUS_OK;
}

// A Samsung-only interface with nothing behind it: "OK" and no data.
static binder_status_t emptyTransact(AIBinder*, transaction_code_t code, const AParcel*, AParcel* out) {
    AStatus* ok = AStatus_newOk();
    binder_status_t st = AParcel_writeStatusHeader(out, ok);
    AStatus_delete(ok);
    if (st != STATUS_OK) return st;
    if (code == 0x00ffffff) return AParcel_writeInt32(out, 1);
    if (code == 0x00fffffe) return AParcel_writeString(out, "notfrozen", 9);
    return STATUS_OK;
}

// The standard interface: every call is passed to the real service and its reply copied back.
static binder_status_t healthTransact(AIBinder*, transaction_code_t code, const AParcel* in, AParcel* out) {
    AParcel* fwd = nullptr;
    AParcel* reply = nullptr;
    binder_status_t st = AIBinder_prepareTransaction(gReal, &fwd);
    if (st != STATUS_OK) return st;
    st = AParcel_appendFrom(in, fwd, 0, AParcel_getDataSize(in));
    if (st == STATUS_OK) st = AIBinder_transact(gReal, code, &fwd, &reply, 0);
    if (st == STATUS_OK && reply) st = AParcel_appendFrom(reply, out, 0, AParcel_getDataSize(reply));
    if (reply) AParcel_delete(reply);
    return st;
}

int main() {
    void* lib = dlopen("libbinder_ndk.so", RTLD_NOW);
    add_service_fn addService = lib ? (add_service_fn)dlsym(lib, "AServiceManager_addService") : nullptr;
    get_service_fn getService = lib ? (get_service_fn)dlsym(lib, "AServiceManager_getService") : nullptr;
    join_pool_fn joinPool = lib ? (join_pool_fn)dlsym(lib, "ABinderProcess_joinThreadPool") : nullptr;
    set_extension_fn setExtension = lib ? (set_extension_fn)dlsym(lib, "AIBinder_setExtension") : nullptr;
    if (!addService || !getService || !joinPool || !setExtension) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "libbinder_ndk functions not found");
        sleep(5);
        return 1;
    }
    for (int i = 0; i < 120 && !gReal; i++) {   // the real health service starts early in the boot; wait for it
        gReal = getService(kHealthName);
        if (!gReal) sleep(1);
    }
    if (!gReal) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "the standard health service never appeared");
        return 1;
    }
    gCallbackClass = AIBinder_Class_define(kCallbackDescriptor, onCreate, onDestroy, sehTransact);
    pthread_t t;
    pthread_create(&t, nullptr, deliverLoop, nullptr);
    AIBinder_Class* sehClass = AIBinder_Class_define(kSehDescriptor, onCreate, onDestroy, sehTransact);
    AIBinder* seh = AIBinder_new(sehClass, nullptr);
    AIBinder_Class* healthClass = AIBinder_Class_define(kHealthDescriptor, onCreate, onDestroy, healthTransact);
    AIBinder_associateClass(gReal, healthClass);   // needed before calls can be passed to the real service
    AIBinder* health = AIBinder_new(healthClass, nullptr);
    binder_status_t ext = setExtension(health, seh);
    binder_status_t a = addService(seh, kSehName);
    binder_status_t b = addService(health, kHealthName);
    __android_log_print(ANDROID_LOG_INFO, kTag, "extension %d, registered %s: %d, forwarding %s: %d", (int)ext, kSehName, (int)a, kHealthName, (int)b);
    for (auto& e : kEmptyServices) {
        AIBinder_Class* cls = AIBinder_Class_define(e[0], onCreate, onDestroy, emptyTransact);
        binder_status_t r = addService(AIBinder_new(cls, nullptr), e[1]);
        __android_log_print(ANDROID_LOG_INFO, kTag, "registered the empty stand-in %s: %d", e[1], (int)r);
    }
    joinPool();
    return 0;
}
