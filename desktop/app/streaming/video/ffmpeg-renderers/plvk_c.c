// This compilation unit contains the implementations of libplacebo header-only libraries.
// These must be compiled as C code, so they cannot be placed inside plvk.cpp.

#ifdef _MSC_VER
#pragma warning(push)
#pragma warning(disable: 4068) // unknown pragma
#pragma warning(disable: 4244) // double -> float truncation warning
#pragma warning(disable: 4267) // size_t -> int truncation warning
#endif

#define PL_LIBAV_IMPLEMENTATION 1
#include <libplacebo/utils/libav.h>
// Ensure we get FFmpeg version macros for accurate prototype matching
#include <libavformat/avformat.h>

#ifdef _MSC_VER
#pragma warning(pop)
#endif

