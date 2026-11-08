/*
 * Which embedded LKM to patch into the ko_target, given the running kernel.
 *
 * This fork keeps exactly one image: dfroot-android12-5.10.ko, built for the
 * iQOO Neo8 (PD2301 / SM8475) on 5.10.246-gki-g8c3d0d7851ff (vivo). The
 * selection code is intentionally trivial - no fallback, no kmi guessing: an
 * unknown kernel is a hard failure and is reported by the caller.
 */
#ifndef DFROOT_KO_SELECT_H
#define DFROOT_KO_SELECT_H

#include <stddef.h>

struct KoImage {
    int kmi;
    int kver_major, kver_minor;
    const char *start, *end;
};

extern char dfroot_ko_12_5_10_start[], dfroot_ko_12_5_10_end[];

static const struct KoImage dfroot_ko_images[] = {
    {12,  5, 10, dfroot_ko_12_5_10_start,  dfroot_ko_12_5_10_end},
};

#define DFROOT_KO_IMAGE_COUNT ((int)(sizeof(dfroot_ko_images) / sizeof(dfroot_ko_images[0])))

static const struct KoImage *select_ko_image(int kmi, int major, int minor,
                                             int *candidates, int *exact)
{
    const struct KoImage *hit = NULL;
    int n = 0;

    if (candidates) *candidates = 0;
    if (exact) *exact = 0;

    for (int i = 0; i < DFROOT_KO_IMAGE_COUNT; i++) {
        const struct KoImage *c = &dfroot_ko_images[i];
        if (c->kver_major != major || c->kver_minor != minor) continue;
        n++;
        if (c->kmi == kmi) hit = c;
    }

    if (candidates) *candidates = n;
    if (exact) *exact = (hit != NULL);
    return hit;
}

#endif /* DFROOT_KO_SELECT_H */
