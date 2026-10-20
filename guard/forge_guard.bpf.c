// SPDX-License-Identifier: GPL-2.0
/* Forge container guard: an experimental BPF security program that would protect the Linux VM from Android
 * running in Forge's virtual devices. Nothing loads it: this kernel cannot attach BPF security programs (it has no
 * function tracer), so the Forge Android guard (forge_android_guard.py) does this work instead.
 *
 * 1. Android's init writes to /proc/sysrq-trigger while it shuts down: "e" and "i" signal every process in the
 *    whole VM, and "c" (after a shutdown that takes too long) crashes the VM's kernel, which closes the Terminal app.
 *    A container cannot hide the file, because Android mounts its own /proc.
 * 2. Android's ueventd writes "add" into every /sys/.../uevent file at start, which replays every device event of
 *    the VM to the VM's own udev.
 *
 * Loaded, it would refuse both writes, but only for processes outside the VM's first PID namespace, that is, inside
 * a container. Everything the VM itself does would be untouched, and counters would show how often it refused. */
#include "vmlinux.h"
#include <bpf/bpf_helpers.h>
#include <bpf/bpf_tracing.h>
#include <bpf/bpf_core_read.h>

#define EPERM 1
#define FMODE_WRITE 0x2
#define PROC_SUPER_MAGIC 0x9fa0
#define SYSFS_MAGIC 0x62656572

char LICENSE[] SEC("license") = "GPL";

__u64 denied_sysrq = 0;
__u64 denied_uevent = 0;

static __always_inline int same(const char *a, const char *b, int n)
{
	for (int i = 0; i < n; i++)
		if (a[i] != b[i])
			return 0;
	return 1;
}

SEC("lsm/file_open")
int BPF_PROG(forge_guard_open, struct file *file)
{
	if (!(BPF_CORE_READ(file, f_mode) & FMODE_WRITE))
		return 0;
	struct dentry *d = BPF_CORE_READ(file, f_path.dentry);
	unsigned long magic = BPF_CORE_READ(d, d_sb, s_magic);
	if (magic != PROC_SUPER_MAGIC && magic != SYSFS_MAGIC)
		return 0;
	struct task_struct *task = bpf_get_current_task_btf();
	if (BPF_CORE_READ(task, thread_pid, level) == 0)
		return 0;   /* the VM itself */
	char name[16] = {};
	bpf_probe_read_kernel_str(name, sizeof(name), BPF_CORE_READ(d, d_name.name));
	if (magic == PROC_SUPER_MAGIC && same(name, "sysrq-trigger", 14)) {
		__sync_fetch_and_add(&denied_sysrq, 1);
		return -EPERM;
	}
	if (magic == SYSFS_MAGIC && same(name, "uevent", 7)) {
		__sync_fetch_and_add(&denied_uevent, 1);
		return -EPERM;
	}
	return 0;
}
