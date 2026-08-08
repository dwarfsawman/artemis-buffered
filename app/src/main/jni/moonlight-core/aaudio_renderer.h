#pragma once

#include <stdbool.h>
#include <stdint.h>

// These entry points let the Opus bridge feed the active AAudio ring without
// crossing into Java for every packet. The implementation serializes teardown
// against in-flight writes, so callers may safely race renderer shutdown.
bool ArtemisAaudioRendererIsActive(void);
int32_t ArtemisAaudioRendererWriteDecoded(const int16_t* samples,
                                          uint32_t frames,
                                          int32_t channelCount);
