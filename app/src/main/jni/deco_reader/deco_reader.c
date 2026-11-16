// Shizuku child process. Never grab a keyboard, relative mouse, or built-in pen.
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>

struct tablet {
    int fd, auxiliary, dropped;
    int x, y, pressure, tx, ty, touch, pen, rubber, buttons, wheel;
};
static volatile sig_atomic_t stopping;
static void stop(int sig) { (void)sig; stopping = 1; }
static long long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}
static int bit(const unsigned char *bits, int n) { return (bits[n / 8] >> (n % 8)) & 1; }
static int axis(int fd, int code) {
    struct input_absinfo a = {0};
    ioctl(fd, EVIOCGABS(code), &a);
    return a.value;
}
static void snapshot(struct tablet *t) {
    unsigned char keys[(KEY_MAX + 8) / 8] = {0};
    ioctl(t->fd, EVIOCGKEY(sizeof(keys)), keys);
    t->x = axis(t->fd, ABS_X); t->y = axis(t->fd, ABS_Y);
    if (t->auxiliary) {
        t->buttons = bit(keys, BTN_LEFT) | (bit(keys, BTN_RIGHT) << 1)
                     | (bit(keys, BTN_MIDDLE) << 2);
    } else {
        t->pressure = axis(t->fd, ABS_PRESSURE);
        t->tx = axis(t->fd, ABS_TILT_X); t->ty = axis(t->fd, ABS_TILT_Y);
        t->touch = bit(keys, BTN_TOUCH); t->pen = bit(keys, BTN_TOOL_PEN);
        t->rubber = bit(keys, BTN_TOOL_RUBBER);
        t->buttons = bit(keys, BTN_STYLUS) | (bit(keys, BTN_STYLUS2) << 1);
    }
}
static int emit(struct tablet *t) {
    char line[160];
    int len;
    if (t->auxiliary) {
        len = snprintf(line, sizeof(line), "M %d %d %d %d\n", t->x, t->y, t->buttons, t->wheel);
        t->wheel = 0;
    } else {
        len = snprintf(line, sizeof(line), "P %d %d %d %d %d %d %d %d %d\n",
                       t->x, t->y, t->pressure, t->tx, t->ty, t->touch,
                       t->pen, t->rubber, t->buttons);
    }
    // Never remain grabbed if the app cannot drain the pipe. A short write is fatal.
    return write(STDOUT_FILENO, line, len) == len;
}
static int open_tablet(const char *path, int auxiliary, const char *identity) {
    int fd = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) return -1;
    struct input_id id = {0};
    char name[128] = {0}, uniq[128] = {0};
    unsigned char absbits[(ABS_MAX + 8) / 8] = {0};
    unsigned char relbits[(REL_MAX + 8) / 8] = {0};
    unsigned char keys[(KEY_MAX + 8) / 8] = {0};
    struct input_absinfo x = {0}, y = {0}, pressure = {0};
    ioctl(fd, EVIOCGID, &id); ioctl(fd, EVIOCGNAME(sizeof(name)), name);
    ioctl(fd, EVIOCGUNIQ(sizeof(uniq)), uniq);
    ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(absbits)), absbits);
    ioctl(fd, EVIOCGBIT(EV_REL, sizeof(relbits)), relbits);
    ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(keys)), keys);
    ioctl(fd, EVIOCGABS(ABS_X), &x); ioctl(fd, EVIOCGABS(ABS_Y), &y);
    ioctl(fd, EVIOCGABS(ABS_PRESSURE), &pressure);
    // Only this modified Bluetooth descriptor has the hard-coded coordinate correction.
    int match = id.bustype == BUS_BLUETOOTH && id.vendor == 0x28bd && id.product == 0x0949
                && x.minimum == 0 && y.minimum == 0 && x.maximum == 32767 && y.maximum == 32767
                && bit(absbits, ABS_X) && bit(absbits, ABS_Y)
                && !bit(relbits, REL_X) && !bit(relbits, REL_Y) && !bit(keys, KEY_A);
    if (auxiliary) {
        match = match && !strcmp(name, "Deco mini7W V2") && !bit(absbits, ABS_PRESSURE)
                && identity[0] && !strcmp(uniq, identity);
    } else {
        match = match && !strcmp(name, "Deco mini7W V2 Pen") && bit(keys, BTN_TOOL_PEN)
                && bit(absbits, ABS_PRESSURE) && pressure.maximum == 8191
                && bit(absbits, ABS_TILT_X) && bit(absbits, ABS_TILT_Y);
    }
    if (match) return fd;
    close(fd);
    return -1;
}
static int find_tablet(int auxiliary, const char *identity) {
    DIR *dir = opendir("/dev/input");
    if (!dir) return -1;
    struct dirent *e;
    int fd = -1;
    while ((e = readdir(dir))) {
        if (strncmp(e->d_name, "event", 5)) continue;
        char path[300];
        snprintf(path, sizeof(path), "/dev/input/%s", e->d_name);
        fd = open_tablet(path, auxiliary, identity);
        if (fd >= 0) { fprintf(stderr, "DecoReader: selected %s (%s)\n", path, auxiliary ? "hover" : "pen"); break; }
    }
    closedir(dir);
    return fd;
}
int main(void) {
    signal(SIGTERM, stop); signal(SIGINT, stop); signal(SIGPIPE, SIG_IGN);
    fcntl(STDOUT_FILENO, F_SETFL, fcntl(STDOUT_FILENO, F_GETFL) | O_NONBLOCK);
    struct tablet t[2] = {{.fd = -1}, {.fd = -1, .auxiliary = 1}};
    char identity[128] = {0};
    t[0].fd = find_tablet(0, "");
    if (t[0].fd < 0) { fprintf(stderr, "DecoReader: compatible Bluetooth pen unavailable\n"); return 2; }
    ioctl(t[0].fd, EVIOCGUNIQ(sizeof(identity)), identity);
    t[1].fd = find_tablet(1, identity);
    int result = 3;
    if (t[1].fd < 0) { fprintf(stderr, "DecoReader: matching hover node unavailable\n"); goto cleanup; }
    for (int i = 0; i < 2; i++) {
        if (ioctl(t[i].fd, EVIOCGRAB, 1) < 0) {
            fprintf(stderr, "DecoReader: EVIOCGRAB failed: %s\n", strerror(errno)); goto cleanup;
        }
        snapshot(&t[i]);
    }
    fprintf(stderr, "DecoReader: grabbed pen + hover; uid=%d\n", getuid());
    if (write(STDOUT_FILENO, "READY\n", 6) != 6 || !emit(&t[0])) goto cleanup;
    long long heartbeat = now_ms();
    result = 0;
    while (!stopping && now_ms() - heartbeat < 1500) {
        struct pollfd p[3] = {{STDIN_FILENO, POLLIN, 0}, {t[0].fd, POLLIN, 0}, {t[1].fd, POLLIN, 0}};
        int n = poll(p, 3, 100);
        if (n < 0) { if (errno == EINTR) continue; break; }
        // Closing stdin, app death, stalled main thread, and focus loss all release both fds.
        if (p[0].revents & (POLLHUP | POLLERR | POLLNVAL)) break;
        if (p[0].revents & POLLIN) {
            char buf[64];
            int count = read(STDIN_FILENO, buf, sizeof(buf));
            if (count <= 0 || memchr(buf, 'Q', count)) break;
            heartbeat = now_ms();
        }
        for (int i = 0; i < 2; i++) {
            if (p[i+1].revents & (POLLHUP | POLLERR | POLLNVAL)) goto cleanup;
            if (!(p[i+1].revents & POLLIN)) continue;
            struct input_event events[32];
            ssize_t bytes = read(t[i].fd, events, sizeof(events));
            if (bytes < 0 && errno == EAGAIN) continue;
            if (bytes <= 0 || bytes % sizeof(events[0])) goto cleanup;
            for (size_t j = 0; j < (size_t)bytes / sizeof(events[0]); j++) {
                struct input_event *e = &events[j];
                if (e->type == EV_SYN && e->code == SYN_DROPPED) {
                    t[i].dropped = 1;
                    if (write(STDOUT_FILENO, "RESET\n", 6) != 6) goto cleanup;
                }
                if (t[i].dropped) {
                    if (e->type != EV_SYN || e->code != SYN_REPORT) continue;
                    snapshot(&t[i]); t[i].wheel = 0; t[i].dropped = 0;
                }
                if (e->type == EV_ABS) {
                    switch (e->code) {
                        case ABS_X: t[i].x = e->value; break;
                        case ABS_Y: t[i].y = e->value; break;
                        case ABS_PRESSURE: t[i].pressure = e->value; break;
                        case ABS_TILT_X: t[i].tx = e->value; break;
                        case ABS_TILT_Y: t[i].ty = e->value; break;
                    }
                } else if (e->type == EV_KEY) {
                    int mask = 0;
                    switch (e->code) {
                        case BTN_TOUCH: t[i].touch = !!e->value; break;
                        case BTN_TOOL_PEN: t[i].pen = !!e->value; break;
                        case BTN_TOOL_RUBBER: t[i].rubber = !!e->value; break;
                        case BTN_STYLUS: case BTN_LEFT: mask = 1; break;
                        case BTN_STYLUS2: case BTN_RIGHT: mask = 2; break;
                        case BTN_MIDDLE: mask = 4; break;
                    }
                    if (mask) t[i].buttons = (t[i].buttons & ~mask) | (e->value ? mask : 0);
                } else if (e->type == EV_REL && e->code == REL_WHEEL) {
                    t[i].wheel += e->value;
                }
                if (e->type == EV_SYN && e->code == SYN_REPORT && !emit(&t[i])) goto cleanup;
            }
        }
    }
cleanup:
    for (int i = 0; i < 2; i++) if (t[i].fd >= 0) { ioctl(t[i].fd, EVIOCGRAB, 0); close(t[i].fd); }
    fprintf(stderr, "DecoReader: released\n");
    return result;
}
