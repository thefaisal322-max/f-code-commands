LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := fcodepty
LOCAL_SRC_FILES := fcode_pty.c
LOCAL_CFLAGS := -std=c11 -Wall -Wextra -Os
# Phones with 16 KB memory pages (Android 15+) need libraries aligned for them.
LOCAL_LDFLAGS := -Wl,-z,max-page-size=16384
include $(BUILD_SHARED_LIBRARY)
