/*
 * libforge_compat.so: answers two questions differently for Google's Android in a container (part of Forge IDE).
 * Forge's Google images load it into Android's process starter (zygote), so Android's system process has it too.
 * Every other call is passed on unchanged.
 *
 * 1. SELinux labels of BPF files. Google's connectivity module checks the SELinux labels of the BPF programs and
 *    maps that Android pins under /sys/fs/bpf, and stops Android's system process when it cannot read them. The VM's
 *    kernel has no SELinux policy (and must never get one), so the BPF file system has no labels to give. When
 *    reading a file's "security.selinux" attribute fails under /sys/fs/bpf, the label Android gives that place is
 *    returned: /sys/fs/bpf is u:object_r:fs_bpf:s0, and everything in /sys/fs/bpf/<name> is u:object_r:fs_bpf_<name>:s0.
 * 2. Owners of process group folders. Android's system process makes a cgroup folder for each sandboxed process (for
 *    example WebView's) and then gives it to the sandbox's user, which the kernel refuses in a container (the system
 *    process may not change owners). The sandbox never needs to own the folder, only to be placed in it, so when
 *    changing an owner under /sys/fs/cgroup is refused, the folder stays with the system user and the call succeeds.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <sys/types.h>

typedef ssize_t (*getxattr_fn)(const char *, const char *, void *, size_t);

static ssize_t label_for(const char *path, const char *name, void *value, size_t size) {
    static const char base[] = "/sys/fs/bpf";
    char label[160];
    if (!path || !name || strcmp(name, "security.selinux") != 0 || strncmp(path, base, sizeof base - 1) != 0) {
        return -2;
    }
    const char *rest = path + sizeof base - 1;
    if (*rest == '\0' || strcmp(rest, "/") == 0) {
        snprintf(label, sizeof label, "u:object_r:fs_bpf:s0");
    } else if (*rest == '/') {
        rest++;
        const char *slash = strchr(rest, '/');
        int len = slash ? (int)(slash - rest) : (int)strlen(rest);
        if (len <= 0 || len > 64) {
            return -2;
        }
        snprintf(label, sizeof label, "u:object_r:fs_bpf_%.*s:s0", len, rest);
    } else {
        return -2;   /* /sys/fs/bpfsomething */
    }
    size_t need = strlen(label) + 1;
    if (size == 0) {
        return (ssize_t)need;
    }
    if (size < need) {
        errno = ERANGE;
        return -1;
    }
    memcpy(value, label, need);
    return (ssize_t)need;
}

static ssize_t answer(getxattr_fn real, const char *path, const char *name, void *value, size_t size) {
    int saved;
    ssize_t r = real ? real(path, name, value, size) : -1;
    if (r >= 0 || (errno != EOPNOTSUPP && errno != ENODATA)) {
        return r;
    }
    saved = errno;
    ssize_t l = label_for(path, name, value, size);
    if (l == -2) {
        errno = saved;
        return r;
    }
    return l;
}

ssize_t lgetxattr(const char *path, const char *name, void *value, size_t size) {
    static getxattr_fn real;
    if (!real) {
        real = (getxattr_fn)dlsym(RTLD_NEXT, "lgetxattr");
    }
    return answer(real, path, name, value, size);
}

ssize_t getxattr(const char *path, const char *name, void *value, size_t size) {
    static getxattr_fn real;
    if (!real) {
        real = (getxattr_fn)dlsym(RTLD_NEXT, "getxattr");
    }
    return answer(real, path, name, value, size);
}

typedef int (*chown_fn)(const char *, uid_t, gid_t);

static int owner(chown_fn real, const char *path, uid_t uid, gid_t gid) {
    int r = real ? real(path, uid, gid) : -1;
    if (r != 0 && errno == EPERM && path && strncmp(path, "/sys/fs/cgroup/", 15) == 0) {
        return 0;
    }
    return r;
}

int lchown(const char *path, uid_t uid, gid_t gid) {
    static chown_fn real;
    if (!real) {
        real = (chown_fn)dlsym(RTLD_NEXT, "lchown");
    }
    return owner(real, path, uid, gid);
}

int chown(const char *path, uid_t uid, gid_t gid) {
    static chown_fn real;
    if (!real) {
        real = (chown_fn)dlsym(RTLD_NEXT, "chown");
    }
    return owner(real, path, uid, gid);
}
