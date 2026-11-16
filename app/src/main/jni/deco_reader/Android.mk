LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := deco_reader
LOCAL_SRC_FILES := deco_reader.c
LOCAL_CFLAGS := -Wall -Wextra -Werror
# Package a PIE executable as libdeco_reader.so, just like the root evdev reader.
# Shizuku executes the installed binary; no root or writable executable copy is needed.
LOCAL_BUILD_SCRIPT := BUILD_EXECUTABLE
LOCAL_MAKEFILE := $(local-makefile)
my := TARGET_
$(call handle-module-filename,lib,$(TARGET_SONAME_EXTENSION))
$(call handle-module-built)
LOCAL_MODULE_CLASS := EXECUTABLE
include $(BUILD_SYSTEM)/build-module.mk
