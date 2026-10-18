/* A stand-in for OnePlus's network feature library (liboplusNetd.so under /system_ext/lib64/oplusex/
 * com.oplus.NetworkAssistSys), which the GSI port leaves out. Without it, netd's OnePlus extension fails to open the
 * library and then crashes on the missing error text (strlen of a null pointer), which restarts netd and zygote every
 * ten seconds and keeps OxygenOS from ever finishing its boot (found 2026-10-17). Every entry point here does nothing
 * and reports that nothing was done; netd's extension checks each one for null before calling it and ignores the
 * results of the ones it calls at start. Part of Forge IDE. */
#include <stddef.h>

__attribute__((visibility("default"))) void* get_instance(void) { return NULL; }
__attribute__((visibility("default"))) void* create(void) { return NULL; }
__attribute__((visibility("default"))) int initIptableRestore(void* a, void* b, void* c) { (void)a; (void)b; (void)c; return 0; }
__attribute__((visibility("default"))) int dispatch_cmd(const void* cmd, const void* args, void* out) { (void)cmd; (void)args; (void)out; return -1; }
__attribute__((visibility("default"))) int dump_cmd(int fd, const void* args) { (void)fd; (void)args; return 0; }
