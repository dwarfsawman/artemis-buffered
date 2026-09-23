#ifndef PL_LIBAV_SHIM_H
#define PL_LIBAV_SHIM_H

#ifdef __cplusplus
extern "C" {
#endif

#include <stdbool.h>
#include <libavutil/frame.h>

// Avoid pulling libplacebo headers into C++ TU; use opaque pointers here.
// They are cast to the correct libplacebo types in the C implementation.
bool pl_map_avframe_simple(const void *gpu, void *out_frame, const AVFrame *frame, void *tex);
void pl_unmap_avframe_simple(const void *gpu, void *frame);

#ifdef __cplusplus
}
#endif

#endif // PL_LIBAV_SHIM_H

