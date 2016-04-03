# Copyright (C) 2016 AllianceROM, ~Morningstar
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

LOCAL_PATH := $(call my-dir)

# Alliance platform library
# =====================================================================
include $(CLEAR_VARS)

alliance_src := src/java/alliance_src

LOCAL_MODULE := com.alliance-rom.platform
LOCAL_MODULE_TAGS := optional

LOCAL_SRC_FILES := \
	$(call all-java-files-under, $(alliance_src)) \

include $(BUILD_JAVA_LIBRARY)

# ====  com.alliance-rom.platform.xml lib def  ========================
include $(CLEAR_VARS)

LOCAL_MODULE := com.alliance-rom.platform.xml
LOCAL_MODULE_TAGS := optional

LOCAL_MODULE_CLASS := ETC

# This will install the file in /system/etc/permissions
LOCAL_MODULE_PATH := $(TARGET_OUT_ETC)/permissions

LOCAL_SRC_FILES := $(LOCAL_MODULE)

include $(BUILD_PREBUILT)

# ====

# build other packages
include $(call first-makefiles-under,$(LOCAL_PATH))