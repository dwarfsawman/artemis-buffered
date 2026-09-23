#include <string.h>
#include <libplacebo/renderer.h>
#include <libplacebo/utils/libav.h>
#include "pl_libav_shim.h"

bool pl_map_avframe_simple(const void *gpu, void *out_frame, const AVFrame *frame, void *tex)
{
    // Use the simple wrapper to avoid version-specific struct details
    return pl_map_avframe((pl_gpu)gpu, (struct pl_frame *)out_frame, (pl_tex *)tex, frame);
}

void pl_unmap_avframe_simple(const void *gpu, void *frame)
{
    pl_unmap_avframe((pl_gpu)gpu, (struct pl_frame *)frame);
}

