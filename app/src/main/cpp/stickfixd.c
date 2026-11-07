// stickfixd: reads the Odin 3's gamepad evdev node, reshapes the analog sticks
// (deadzone, anti-deadzone, response curve) and re-emits everything through a
// /dev/uinput virtual gamepad. The original node is grabbed and (optionally)
// unlinked so Android only sees the virtual pad.
//
// Started as root by AYN's "Run script as Root" through stickfix-start.sh.
// The StickFix app talks to it over a localhost TCP socket (config + live view).

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <math.h>
#include <netinet/in.h>
#include <poll.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <sys/xattr.h>
#include <time.h>
#include <unistd.h>

#define VERSION "0.1"
#define OUR_PHYS "stickfix/input0"
#define MAX_CLIENTS 4
#define BITS_LONGS(n) (((n) + 8 * sizeof(long) - 1) / (8 * sizeof(long)))
#define TEST_BIT(b, a) (((a)[(b) / (8 * sizeof(long))] >> ((b) % (8 * sizeof(long)))) & 1)

// ---------------------------------------------------------------- config

typedef struct {
    double inner;   // radial deadzone, 0..1 of full deflection
    double outer;   // radius treated as full deflection
    double anti;    // output jumps to this as soon as the stick leaves the deadzone
    double curve;   // exponent; <1 more sensitive near center, >1 less
} StickCfg;

typedef struct {
    StickCfg l, r;
    int hide;
    char token[64];
} Config;

static Config cfg = {
    .l = {0.0, 1.0, 0.0, 1.0},
    .r = {0.0, 1.0, 0.0, 1.0},
    .hide = 1,
};

static const char *config_path;
static time_t config_mtime;
static int port = 47811;
static const char *forced_dev;

static void logf_(const char *fmt, ...) {
    va_list ap;
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    struct tm tm;
    localtime_r(&ts.tv_sec, &tm);
    fprintf(stderr, "%02d:%02d:%02d ", tm.tm_hour, tm.tm_min, tm.tm_sec);
    va_start(ap, fmt);
    vfprintf(stderr, fmt, ap);
    va_end(ap);
    fputc('\n', stderr);
    fflush(stderr);
}
#define LOG(...) logf_(__VA_ARGS__)

static double clampd(double v, double lo, double hi) { return v < lo ? lo : v > hi ? hi : v; }

static void sanitize(StickCfg *s) {
    s->inner = clampd(s->inner, 0, 0.9);
    s->outer = clampd(s->outer, s->inner + 0.05, 1.5);
    s->anti = clampd(s->anti, 0, 0.9);
    s->curve = clampd(s->curve, 0.2, 5);
}

// Applies one "key=value" token. Returns 1 if the key was known.
static int apply_kv(Config *c, const char *key, const char *val) {
    StickCfg *s = NULL;
    if (!strncmp(key, "l.", 2)) s = &c->l;
    else if (!strncmp(key, "r.", 2)) s = &c->r;
    if (s) {
        const char *k = key + 2;
        double v = atof(val);
        if (!strcmp(k, "inner")) s->inner = v;
        else if (!strcmp(k, "outer")) s->outer = v;
        else if (!strcmp(k, "anti")) s->anti = v;
        else if (!strcmp(k, "curve")) s->curve = v;
        else return 0;
        sanitize(s);
        return 1;
    }
    if (!strcmp(key, "hide")) { c->hide = atoi(val) != 0; return 1; }
    if (!strcmp(key, "token")) { snprintf(c->token, sizeof c->token, "%s", val); return 1; }
    return 0;
}

// Parses whitespace/newline separated key=value tokens.
static void apply_text(Config *c, char *text, int allow_token) {
    char *save = NULL;
    for (char *tok = strtok_r(text, " \t\r\n", &save); tok; tok = strtok_r(NULL, " \t\r\n", &save)) {
        if (tok[0] == '#') continue;
        char *eq = strchr(tok, '=');
        if (!eq) continue;
        *eq = 0;
        if (!allow_token && !strcmp(tok, "token")) continue;
        apply_kv(c, tok, eq + 1);
    }
}

static void load_config_file(int force) {
    struct stat st;
    if (!config_path || stat(config_path, &st) != 0) return;
    if (!force && st.st_mtime == config_mtime) return;
    config_mtime = st.st_mtime;
    FILE *f = fopen(config_path, "r");
    if (!f) return;
    char buf[4096];
    size_t n = fread(buf, 1, sizeof buf - 1, f);
    fclose(f);
    buf[n] = 0;
    apply_text(&cfg, buf, 1);
    LOG("config loaded: L in=%.3f out=%.3f anti=%.3f curve=%.2f | R in=%.3f out=%.3f anti=%.3f curve=%.2f hide=%d",
        cfg.l.inner, cfg.l.outer, cfg.l.anti, cfg.l.curve, cfg.r.inner, cfg.r.outer, cfg.r.anti, cfg.r.curve, cfg.hide);
}

static int format_config(char *out, size_t cap) {
    return snprintf(out, cap,
                    "CFG l.inner=%.4f l.outer=%.4f l.anti=%.4f l.curve=%.3f "
                    "r.inner=%.4f r.outer=%.4f r.anti=%.4f r.curve=%.3f hide=%d\n",
                    cfg.l.inner, cfg.l.outer, cfg.l.anti, cfg.l.curve,
                    cfg.r.inner, cfg.r.outer, cfg.r.anti, cfg.r.curve, cfg.hide);
}

// ---------------------------------------------------------------- stick math

// Maps a raw stick position (normalized -1..1 per axis) to the output position.
static void shape(const StickCfg *s, double x, double y, double *ox, double *oy) {
    double r = sqrt(x * x + y * y);
    if (r <= s->inner || r < 1e-6) { *ox = *oy = 0; return; }
    double t = (r - s->inner) / (s->outer - s->inner);
    if (t > 1) t = 1;
    t = pow(t, s->curve);
    double out = s->anti + (1 - s->anti) * t;
    double k = out / r;
    *ox = clampd(x * k, -1, 1);
    *oy = clampd(y * k, -1, 1);
}

// ---------------------------------------------------------------- devices

typedef struct {
    int fd;
    char path[64];
    dev_t rdev;
    mode_t mode;
    uid_t uid;
    gid_t gid;
    char label[128];
    int hidden;
    struct input_id id;
    char name[128];
    unsigned long keybits[BITS_LONGS(KEY_CNT)];
    unsigned long absbits[BITS_LONGS(ABS_CNT)];
    struct input_absinfo abs[ABS_CNT];
} Source;

static Source src = {.fd = -1};
static int ufd = -1;
static struct input_absinfo uabs[ABS_CNT];  // the virtual pad's ranges

// Stick axes on the Odin 3: left ABS_X/ABS_Y, right ABS_Z/ABS_RZ.
static const int AX_LX = ABS_X, AX_LY = ABS_Y, AX_RX = ABS_Z, AX_RY = ABS_RZ;
static int raw[ABS_CNT];
static int sent[ABS_CNT];

static int is_stick_axis(int code) { return code == AX_LX || code == AX_LY || code == AX_RX || code == AX_RY; }

static double norm(int code, int v) {
    struct input_absinfo *a = &src.abs[code];
    double c = (a->minimum + a->maximum) / 2.0, h = (a->maximum - a->minimum) / 2.0;
    return h > 0 ? clampd((v - c) / h, -1.5, 1.5) : 0;
}

static int denorm(int code, double v) {
    struct input_absinfo *a = &uabs[code];
    double c = (a->minimum + a->maximum) / 2.0, h = (a->maximum - a->minimum) / 2.0;
    return (int)lround(c + v * h);
}

// Finds the gamepad node: the one exposing BTN_GAMEPAD and ABS_X that isn't ours.
static int find_source(char *path, size_t cap) {
    if (forced_dev) { snprintf(path, cap, "%s", forced_dev); return 0; }
    DIR *d = opendir("/dev/input");
    if (!d) return -1;
    struct dirent *e;
    int found = -1;
    while ((e = readdir(d))) {
        if (strncmp(e->d_name, "event", 5)) continue;
        char p[64];
        snprintf(p, sizeof p, "/dev/input/%s", e->d_name);
        int fd = open(p, O_RDONLY | O_CLOEXEC);
        if (fd < 0) continue;
        char phys[64] = "";
        ioctl(fd, EVIOCGPHYS(sizeof phys), phys);
        unsigned long kb[BITS_LONGS(KEY_CNT)] = {0}, ab[BITS_LONGS(ABS_CNT)] = {0};
        ioctl(fd, EVIOCGBIT(EV_KEY, sizeof kb), kb);
        ioctl(fd, EVIOCGBIT(EV_ABS, sizeof ab), ab);
        close(fd);
        if (strcmp(phys, OUR_PHYS) && TEST_BIT(BTN_GAMEPAD, kb) && TEST_BIT(ABS_X, ab) && TEST_BIT(AX_RX, ab)) {
            snprintf(path, cap, "%s", p);
            found = 0;
            break;
        }
    }
    closedir(d);
    return found;
}

// Puts the hidden node back. Android's EventHub opens it on IN_CREATE, so the node
// is built off to the side with the original owner, mode and SELinux label and then
// hard-linked into place; otherwise EventHub races us and gets EACCES.
static void restore_node(void) {
    if (!src.hidden) return;
    struct stat st;
    if (stat(src.path, &st) != 0) {
        const char *tmp = "/dev/.stickfix-node";
        unlink(tmp);
        if (mknod(tmp, S_IFCHR | (src.mode & 07777), src.rdev) == 0) {
            chown(tmp, src.uid, src.gid);
            chmod(tmp, src.mode & 07777);
            if (src.label[0]) setxattr(tmp, "security.selinux", src.label, strlen(src.label) + 1, 0);
            if (link(tmp, src.path) == 0) LOG("restored %s", src.path);
            else LOG("could not restore %s: %s", src.path, strerror(errno));
            unlink(tmp);
        } else {
            LOG("could not restore %s: %s", src.path, strerror(errno));
        }
    }
    src.hidden = 0;
}

static void close_source(void) {
    if (src.fd >= 0) {
        ioctl(src.fd, EVIOCGRAB, 0);
        close(src.fd);
    }
    restore_node();
    src.fd = -1;
}

static int open_source(void) {
    char path[64];
    if (find_source(path, sizeof path) != 0) return -1;
    int fd = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) { LOG("open %s: %s", path, strerror(errno)); return -1; }
    Source s = {.fd = fd};
    snprintf(s.path, sizeof s.path, "%s", path);
    ioctl(fd, EVIOCGID, &s.id);
    ioctl(fd, EVIOCGNAME(sizeof s.name), s.name);
    ioctl(fd, EVIOCGBIT(EV_KEY, sizeof s.keybits), s.keybits);
    ioctl(fd, EVIOCGBIT(EV_ABS, sizeof s.absbits), s.absbits);
    for (int a = 0; a < ABS_CNT; a++)
        if (TEST_BIT(a, s.absbits)) ioctl(fd, EVIOCGABS(a), &s.abs[a]);
    struct stat st;
    fstat(fd, &st);
    s.rdev = st.st_rdev;
    s.mode = st.st_mode;
    s.uid = st.st_uid;
    s.gid = st.st_gid;
    if (getxattr(path, "security.selinux", s.label, sizeof s.label - 1) < 0) s.label[0] = 0;
    src = s;
    for (int a = 0; a < ABS_CNT; a++) raw[a] = src.abs[a].value;
    LOG("source %s \"%s\" %04x:%04x", src.path, src.name, src.id.vendor, src.id.product);

    if (ioctl(fd, EVIOCGRAB, 1) != 0) LOG("EVIOCGRAB failed: %s (continuing)", strerror(errno));
    if (cfg.hide) {
        if (unlink(src.path) == 0) { src.hidden = 1; LOG("hid %s from Android", src.path); }
        else LOG("could not hide %s: %s", src.path, strerror(errno));
    }
    return 0;
}

static int create_uinput(void) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) { LOG("open /dev/uinput: %s", strerror(errno)); return -1; }
    ioctl(fd, UI_SET_EVBIT, EV_SYN);
    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_EVBIT, EV_ABS);
    for (int k = 0; k < KEY_CNT; k++)
        if (TEST_BIT(k, src.keybits)) ioctl(fd, UI_SET_KEYBIT, k);
    for (int a = 0; a < ABS_CNT; a++) {
        if (!TEST_BIT(a, src.absbits)) continue;
        struct uinput_abs_setup as = {.code = a, .absinfo = src.abs[a]};
        // Android's InputReader turns `flat` into a deadzone; we do our own.
        as.absinfo.flat = 0;
        as.absinfo.fuzz = 0;
        as.absinfo.value = is_stick_axis(a) ? (as.absinfo.minimum + as.absinfo.maximum) / 2 : as.absinfo.value;
        uabs[a] = as.absinfo;
        ioctl(fd, UI_SET_ABSBIT, a);
        if (ioctl(fd, UI_ABS_SETUP, &as) != 0) LOG("UI_ABS_SETUP %d: %s", a, strerror(errno));
    }
    ioctl(fd, UI_SET_PHYS, OUR_PHYS);
    struct uinput_setup us = {0};
    // Same IDs as the original so Android picks the same key layout file.
    us.id = src.id;
    snprintf(us.name, sizeof us.name, "%s", src.name);
    if (ioctl(fd, UI_DEV_SETUP, &us) != 0 || ioctl(fd, UI_DEV_CREATE) != 0) {
        LOG("uinput create: %s", strerror(errno));
        close(fd);
        return -1;
    }
    for (int a = 0; a < ABS_CNT; a++) sent[a] = INT32_MIN;
    LOG("virtual pad created");
    return fd;
}

// ---------------------------------------------------------------- output

static struct input_event obuf[256];
static int on;

static void emit(int type, int code, int value) {
    if (on < (int)(sizeof obuf / sizeof obuf[0])) obuf[on++] = (struct input_event){.type = type, .code = code, .value = value};
}

static double out_l[2], out_r[2];

static void emit_sticks(void) {
    shape(&cfg.l, norm(AX_LX, raw[AX_LX]), norm(AX_LY, raw[AX_LY]), &out_l[0], &out_l[1]);
    shape(&cfg.r, norm(AX_RX, raw[AX_RX]), norm(AX_RY, raw[AX_RY]), &out_r[0], &out_r[1]);
    int codes[4] = {AX_LX, AX_LY, AX_RX, AX_RY};
    double vals[4] = {out_l[0], out_l[1], out_r[0], out_r[1]};
    for (int i = 0; i < 4; i++) {
        int v = denorm(codes[i], vals[i]);
        if (v != sent[codes[i]]) { emit(EV_ABS, codes[i], v); sent[codes[i]] = v; }
    }
}

static void flush_out(void) {
    if (!on) return;
    emit(EV_SYN, SYN_REPORT, 0);
    ssize_t n = write(ufd, obuf, on * sizeof obuf[0]);
    (void)n;
    on = 0;
}

// ---------------------------------------------------------------- clients

typedef struct {
    int fd;
    int authed;
    int watch;
    char in[1024];
    int inlen;
} Client;

static Client clients[MAX_CLIENTS];
static int lfd = -1;
static long long last_tele_ms;

static long long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000LL + ts.tv_nsec / 1000000;
}

static void client_send(Client *c, const char *s, int len) {
    if (send(c->fd, s, len, MSG_DONTWAIT | MSG_NOSIGNAL) < 0 && errno != EAGAIN && errno != EWOULDBLOCK) {
        close(c->fd);
        c->fd = -1;
    }
}

static void send_telemetry(int force) {
    long long t = now_ms();
    if (!force && t - last_tele_ms < 16) return;
    last_tele_ms = t;
    char line[160];
    int n = snprintf(line, sizeof line, "S %d %d %d %d %d %d %d %d\n",
                     (int)lround(norm(AX_LX, raw[AX_LX]) * 10000), (int)lround(norm(AX_LY, raw[AX_LY]) * 10000),
                     (int)lround(norm(AX_RX, raw[AX_RX]) * 10000), (int)lround(norm(AX_RY, raw[AX_RY]) * 10000),
                     (int)lround(out_l[0] * 10000), (int)lround(out_l[1] * 10000),
                     (int)lround(out_r[0] * 10000), (int)lround(out_r[1] * 10000));
    for (int i = 0; i < MAX_CLIENTS; i++)
        if (clients[i].fd >= 0 && clients[i].authed && clients[i].watch) client_send(&clients[i], line, n);
}

static void handle_line(Client *c, char *line) {
    char reply[512];
    if (!strncmp(line, "AUTH ", 5)) {
        c->authed = cfg.token[0] && !strcmp(line + 5, cfg.token);
        client_send(c, c->authed ? "OK " VERSION "\n" : "ERR auth\n", c->authed ? 4 + (int)strlen(VERSION) : 9);
        if (c->authed) client_send(c, reply, format_config(reply, sizeof reply));
        return;
    }
    if (!c->authed) { client_send(c, "ERR auth\n", 9); return; }
    if (!strncmp(line, "SET ", 4)) {
        apply_text(&cfg, line + 4, 0);
        client_send(c, reply, format_config(reply, sizeof reply));
        // Re-shape immediately so a resting stick reflects the new settings.
        if (ufd >= 0 && src.fd >= 0) { emit_sticks(); flush_out(); send_telemetry(1); }
    } else if (!strcmp(line, "GET")) {
        client_send(c, reply, format_config(reply, sizeof reply));
    } else if (!strncmp(line, "WATCH ", 6)) {
        c->watch = atoi(line + 6);
        if (c->watch) send_telemetry(1);
    } else if (!strcmp(line, "STATUS")) {
        int n = snprintf(reply, sizeof reply, "STATUS dev=%s name=\"%s\" hidden=%d\n",
                         src.fd >= 0 ? src.path : "-", src.name, src.hidden);
        client_send(c, reply, n);
    } else if (!strcmp(line, "QUIT")) {
        kill(getpid(), SIGTERM);
    } else {
        client_send(c, "ERR cmd\n", 8);
    }
}

static void client_read(Client *c) {
    ssize_t n = recv(c->fd, c->in + c->inlen, sizeof c->in - 1 - c->inlen, MSG_DONTWAIT);
    if (n <= 0) {
        if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) return;
        close(c->fd);
        c->fd = -1;
        return;
    }
    c->inlen += n;
    c->in[c->inlen] = 0;
    char *start = c->in, *nl;
    while (c->fd >= 0 && (nl = strchr(start, '\n'))) {
        *nl = 0;
        if (nl > start && nl[-1] == '\r') nl[-1] = 0;
        handle_line(c, start);
        start = nl + 1;
    }
    if (c->fd < 0) return;
    c->inlen = (int)strlen(start);
    memmove(c->in, start, c->inlen + 1);
    if (c->inlen >= (int)sizeof c->in - 1) c->inlen = 0;  // overlong line, drop it
}

static int listen_tcp(void) {
    int fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if (fd < 0) return -1;
    int one = 1;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof one);
    struct sockaddr_in a = {.sin_family = AF_INET, .sin_port = htons(port), .sin_addr.s_addr = htonl(INADDR_LOOPBACK)};
    if (bind(fd, (struct sockaddr *)&a, sizeof a) != 0 || listen(fd, 4) != 0) {
        LOG("listen 127.0.0.1:%d: %s", port, strerror(errno));
        close(fd);
        return -1;
    }
    LOG("listening on 127.0.0.1:%d", port);
    return fd;
}

// ---------------------------------------------------------------- main

static volatile sig_atomic_t quit;
static void on_signal(int sig) { (void)sig; quit = 1; }

static void handle_source_events(void) {
    struct input_event ev[64];
    for (;;) {
        ssize_t n = read(src.fd, ev, sizeof ev);
        if (n < 0) {
            if (errno == EAGAIN) return;
            LOG("source read: %s, reattaching", strerror(errno));
            close_source();
            return;
        }
        if (n == 0) return;
        for (size_t i = 0; i < n / sizeof ev[0]; i++) {
            struct input_event *e = &ev[i];
            if (e->type == EV_SYN && e->code == SYN_REPORT) {
                emit_sticks();
                flush_out();
                send_telemetry(0);
            } else if (e->type == EV_SYN && e->code == SYN_DROPPED) {
                on = 0;
            } else if (e->type == EV_ABS && is_stick_axis(e->code)) {
                raw[e->code] = e->value;
            } else if (e->type == EV_KEY || e->type == EV_ABS) {
                emit(e->type, e->code, e->value);
            }
        }
    }
}

int main(int argc, char **argv) {
    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "--config") && i + 1 < argc) config_path = argv[++i];
        else if (!strcmp(argv[i], "--port") && i + 1 < argc) port = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--dev") && i + 1 < argc) forced_dev = argv[++i];
        else if (!strcmp(argv[i], "--version")) { puts(VERSION); return 0; }
    }
    LOG("stickfixd " VERSION " starting, uid=%d", getuid());
    signal(SIGTERM, on_signal);
    signal(SIGINT, on_signal);
    signal(SIGHUP, SIG_IGN);
    signal(SIGPIPE, SIG_IGN);
    for (int i = 0; i < MAX_CLIENTS; i++) clients[i].fd = -1;
    load_config_file(1);

    while (!quit && open_source() != 0) {
        LOG("no gamepad node yet, retrying");
        sleep(1);
    }
    if (quit) return 0;
    ufd = create_uinput();
    if (ufd < 0) { close_source(); return 1; }
    lfd = listen_tcp();

    long long last_cfg_check = 0, last_retry = 0;
    while (!quit) {
        struct pollfd p[2 + MAX_CLIENTS];
        int np = 0;
        int src_i = -1, l_i = -1, cl_i[MAX_CLIENTS];
        if (src.fd >= 0) { src_i = np; p[np++] = (struct pollfd){.fd = src.fd, .events = POLLIN}; }
        if (lfd >= 0) { l_i = np; p[np++] = (struct pollfd){.fd = lfd, .events = POLLIN}; }
        for (int i = 0; i < MAX_CLIENTS; i++) {
            cl_i[i] = -1;
            if (clients[i].fd >= 0) { cl_i[i] = np; p[np++] = (struct pollfd){.fd = clients[i].fd, .events = POLLIN}; }
        }
        int r = poll(p, np, 250);
        if (r < 0 && errno != EINTR) break;

        if (src_i >= 0 && (p[src_i].revents & (POLLIN | POLLERR | POLLHUP))) {
            if (p[src_i].revents & (POLLERR | POLLHUP)) { LOG("source gone, reattaching"); close_source(); }
            else handle_source_events();
        }
        if (l_i >= 0 && (p[l_i].revents & POLLIN)) {
            int cfd = accept4(lfd, NULL, NULL, SOCK_CLOEXEC | SOCK_NONBLOCK);
            if (cfd >= 0) {
                int slot = -1;
                for (int i = 0; i < MAX_CLIENTS; i++) if (clients[i].fd < 0) { slot = i; break; }
                if (slot < 0) close(cfd);
                else clients[slot] = (Client){.fd = cfd};
            }
        }
        for (int i = 0; i < MAX_CLIENTS; i++)
            if (cl_i[i] >= 0 && clients[i].fd >= 0 && (p[cl_i[i]].revents & (POLLIN | POLLHUP | POLLERR))) client_read(&clients[i]);

        long long t = now_ms();
        if (t - last_cfg_check >= 500) { last_cfg_check = t; load_config_file(0); }
        // AYN's mapping service may recreate its device (mode switch, resume): follow it.
        if (src.fd < 0 && t - last_retry >= 300) { last_retry = t; open_source(); }
    }

    LOG("stopping");
    close_source();
    if (ufd >= 0) { ioctl(ufd, UI_DEV_DESTROY); close(ufd); }
    for (int i = 0; i < MAX_CLIENTS; i++) if (clients[i].fd >= 0) close(clients[i].fd);
    if (lfd >= 0) close(lfd);
    return 0;
}
