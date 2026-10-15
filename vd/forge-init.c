/*
 * forge-init: the first program of the Android TV 16 virtual device (part of Forge IDE).
 *
 * WayDroid-ATV's Android TV 16 build predates WayDroid's Docker support and expects the container that WayDroid's
 * own LXC setup gives it. This program prepares what differs in a Podman container, inside the container only,
 * and then starts Android's init in its place with the same arguments:
 *
 *  - /dev: Android's first stage creates /dev/kmsg, /dev/random, /dev/urandom and /dev/null itself and stops if
 *    they already exist, and Podman puts the VM's device nodes there. So a fresh /dev is mounted on top, with
 *    copies of Podman's character devices (the graphics render node, FUSE, the terminal and the rest) and of the
 *    loop devices Android's system packages need. The VM's disks are not copied in. The graphics, video and DMA
 *    buffer copies are opened to Android's users, as WayDroid's container manager does. The container also gets its
 *    own pseudo-terminal file system at /dev/pts, which this build expects from WayDroid's container setup.
 *  - device-mapper: Android's package manager (apexd) creates device-mapper entries named after its packages, and
 *    those live in the VM's shared kernel, where they would stay behind after the container stops and block the
 *    next start. /dev/device-mapper is therefore a locked placeholder that leads nowhere, so apexd mounts its
 *    packages straight from loop devices, which the kernel releases on its own.
 *  - cgroups: Android's init turns on cgroup controllers (memory, cpu and others) for its app and system groups.
 *    Under cgroup v2 a group can only do that while no process sits in it directly, so this program first moves
 *    itself, and with it init, into a child group called "init".
 *  - binder: WayDroid's container manager creates Android's three binder devices, so this build never does. This
 *    program mounts a private binder file system (binderfs) in the container, makes sure binder, hwbinder and
 *    vndbinder exist in it, opens them to Android's users and links them into /dev. Each container gets its own
 *    binder instance, so virtual devices cannot reach each other.
 */
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/android/binderfs.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mount.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <unistd.h>

static void move_to_child_cgroup(void) {
    if (mkdir("/sys/fs/cgroup/init", 0755) != 0 && errno == EROFS) {
        if (mount(NULL, "/sys/fs/cgroup", NULL, MS_REMOUNT, NULL) != 0) {
            perror("forge-init: making /sys/fs/cgroup writable failed");
        }
        mkdir("/sys/fs/cgroup/init", 0755);
    }
    int fd = open("/sys/fs/cgroup/init/cgroup.procs", O_WRONLY | O_CLOEXEC);
    if (fd < 0) {
        perror("forge-init: opening the init cgroup failed");
        return;
    }
    if (write(fd, "0", 1) != 1) {   /* "0" moves the writing process */
        perror("forge-init: moving into the init cgroup failed");
    }
    close(fd);
}

struct node { char path[128]; mode_t mode; dev_t rdev; };
static struct node nodes[1024];
static int node_count;

static int skipped(const char *rel, mode_t mode) {
    static const char *own[] = {"kmsg", "random", "urandom", "null", "ptmx"};   /* Android's or set up below */
    for (unsigned i = 0; i < sizeof own / sizeof own[0]; i++) {
        if (strcmp(rel, own[i]) == 0) {
            return 1;
        }
    }
    if (strncmp(rel, "mapper", 6) == 0 || strncmp(rel, "dm-", 3) == 0) {   /* device-mapper: see above */
        return 1;
    }
    return S_ISBLK(mode) && strncmp(rel, "loop", 4) != 0;   /* of the block devices, only loop devices */
}

static void remember_nodes(const char *dir, const char *rel, int depth) {
    DIR *d = opendir(dir);
    struct dirent *e;
    if (!d) {
        return;
    }
    while ((e = readdir(d)) != NULL && node_count < (int)(sizeof nodes / sizeof nodes[0])) {
        char path[300], sub[300];
        struct stat st;
        if (e->d_name[0] == '.' || strlen(e->d_name) > 100 || strlen(rel) > 20) {
            continue;
        }
        snprintf(path, sizeof path, "%s/%s", dir, e->d_name);
        snprintf(sub, sizeof sub, "%s%s%s", rel, *rel ? "/" : "", e->d_name);
        if (lstat(path, &st) != 0) {
            continue;
        }
        if ((S_ISCHR(st.st_mode) || S_ISBLK(st.st_mode)) && !skipped(sub, st.st_mode) && strlen(sub) < sizeof nodes[0].path) {
            memcpy(nodes[node_count].path, sub, strlen(sub) + 1);
            nodes[node_count].mode = st.st_mode;
            nodes[node_count].rdev = st.st_rdev;
            node_count++;
        } else if (S_ISDIR(st.st_mode) && depth < 2 && strcmp(sub, "pts") && strcmp(sub, "shm") && strcmp(sub, "mqueue") &&
                   strcmp(sub, "mapper")) {
            remember_nodes(path, sub, depth + 1);
        }
    }
    closedir(d);
}

/* The nodes WayDroid's container manager opens to Android's own users (graphics, video, DMA buffers), because
   Android's graphics services do not run as root. Only the container's own copies are changed, not the VM's. */
static int shared_with_android(const char *rel) {
    static const char *prefixes[] = {"dri/", "dma_heap/", "fb", "video", "sw_sync", "ion", "graphics/"};
    for (unsigned i = 0; i < sizeof prefixes / sizeof prefixes[0]; i++) {
        if (strncmp(rel, prefixes[i], strlen(prefixes[i])) == 0) {
            return 1;
        }
    }
    return 0;
}

static void restore_nodes(void) {
    for (int i = 0; i < node_count; i++) {
        char path[160], *slash;
        snprintf(path, sizeof path, "/dev/%s", nodes[i].path);
        for (slash = strchr(path + 5, '/'); slash; slash = strchr(slash + 1, '/')) {   /* parent folders */
            *slash = 0;
            mkdir(path, 0755);
            *slash = '/';
        }
        if (mknod(path, nodes[i].mode, nodes[i].rdev) != 0 && errno != EEXIST) {
            perror("forge-init: copying a device node failed");
        }
        if (shared_with_android(nodes[i].path)) {
            chmod(path, 0666);
        }
    }
}

static void lock_device_mapper(void) {
    /* A null device that Android's device manager cannot replace: its mknod finds the name taken, and the bind
       mount keeps the name from being removed. Opening it gives a file that answers no device-mapper requests. */
    if (mknod("/dev/device-mapper", S_IFCHR | 0600, makedev(1, 3)) != 0 ||
        mount("/dev/device-mapper", "/dev/device-mapper", NULL, MS_BIND, NULL) != 0) {
        perror("forge-init: locking /dev/device-mapper failed");
    }
}

static void make_pts(void) {
    mkdir("/dev/pts", 0755);
    if (mount("devpts", "/dev/pts", "devpts", MS_NOSUID | MS_NOEXEC, "newinstance,ptmxmode=0666,mode=0620,gid=5") != 0) {
        perror("forge-init: mounting /dev/pts failed");
    }
    if (symlink("pts/ptmx", "/dev/ptmx") != 0) {
        perror("forge-init: linking /dev/ptmx failed");
    }
}

static void make_binder_devices(void) {
    static const char *names[] = {"binder", "hwbinder", "vndbinder"};
    mkdir("/dev/binderfs", 0755);
    if (mount("binder", "/dev/binderfs", "binder", 0, "stats=global") != 0) {
        perror("forge-init: mounting binderfs failed");
        return;
    }
    int control = open("/dev/binderfs/binder-control", O_RDWR | O_CLOEXEC);
    for (unsigned i = 0; i < sizeof names / sizeof names[0]; i++) {
        char path[64], link[64];
        snprintf(path, sizeof path, "/dev/binderfs/%s", names[i]);
        if (access(path, F_OK) != 0 && control >= 0) {   /* the kernel may already have made it */
            struct binderfs_device device;
            memset(&device, 0, sizeof device);
            strncpy(device.name, names[i], BINDERFS_MAX_NAME);
            if (ioctl(control, BINDER_CTL_ADD, &device) != 0) {
                perror("forge-init: creating a binder device failed");
            }
        }
        chmod(path, 0666);   /* Android's services run as their own users */
        snprintf(link, sizeof link, "/dev/%s", names[i]);
        if (symlink(path, link) != 0) {
            perror("forge-init: linking a binder device failed");
        }
    }
    if (control >= 0) {
        close(control);
    }
}

int main(int argc, char **argv) {
    (void)argc;
    move_to_child_cgroup();
    remember_nodes("/dev", "", 0);
    if (mount("tmpfs", "/dev", "tmpfs", MS_NOSUID, "mode=0755") != 0) {
        perror("forge-init: mounting a fresh /dev failed");
    }
    umask(0);
    restore_nodes();
    umask(022);
    lock_device_mapper();
    make_pts();
    make_binder_devices();
    argv[0] = "/init";
    execv("/init", argv);
    perror("forge-init: starting /init failed");
    return 1;
}
