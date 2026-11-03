cmake_minimum_required(VERSION 3.22.1)
project(virtualapp_native LANGUAGES C CXX)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(VA_TARGET_NAME "v++_64")
    set(VA_CORE_SO_NAME "libv++_64.so")
    set(VA_IO_RELOCATOR_SRC "Foundation/IORelocator64.cpp")
else()
    set(VA_TARGET_NAME "v++")
    set(VA_CORE_SO_NAME "libv++.so")
    set(VA_IO_RELOCATOR_SRC "Foundation/IORelocator.cpp")
endif()

add_library(${VA_TARGET_NAME} SHARED
    Jni/VAJni.cpp
    Jni/Helper.cpp
    Foundation/syscall/BinarySyscallFinder.cpp
    Foundation/fake_dlfcn.cpp
    Foundation/canonicalize_md.c
    Foundation/MapsRedirector.cpp
    Foundation/LimbusTranslationRuntime.cpp
    ${VA_IO_RELOCATOR_SRC}
    Foundation/VMHook.cpp
    Foundation/Symbol.cpp
    Foundation/SandboxFs.cpp
    Substrate/hde64.c
    Substrate/SubstrateDebug.cpp
    Substrate/SubstrateHook.cpp
    Substrate/SubstratePosixMemory.cpp
    Substrate/And64InlineHook.cpp
    transparentED/ff_Recognizer.cpp
    transparentED/EncryptFile.cpp
    transparentED/originalInterface.cpp
    transparentED/ctr/caesar_cipher.cpp
    transparentED/ctr/crypter.cpp
    transparentED/ctr/ctr.cpp
    transparentED/ctr/rng.cpp
    transparentED/ctr/SpookyV2.cpp
    transparentED/ctr/util.cpp
    transparentED/ctr/sm4.c
    transparentED/ctr/sm4_cipher.cpp
    transparentED/virtualFileSystem.cpp
    transparentED/fileCoder1.cpp
    transparentED/TemplateFile.cpp
    transparentED/IgnoreFile.cpp
    transparentED/encryptInfoMgr.cpp
    safekey/safekey_jni.cpp
    utils/zJNIEnv.cpp
    utils/utils.cpp
    utils/md5.c
    utils/zMd5.cpp
    utils/controllerManagerNative.cpp
)

target_include_directories(${VA_TARGET_NAME} PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/Foundation
    ${CMAKE_CURRENT_SOURCE_DIR}/Jni
)

target_compile_definitions(${VA_TARGET_NAME} PRIVATE
    NDEBUG
    CORE_SO_NAME="${VA_CORE_SO_NAME}"
    LOG_TAG="VA++"
)

target_compile_options(${VA_TARGET_NAME} PRIVATE
    -O2
    -Wno-error=format-security
    $<$<COMPILE_LANGUAGE:CXX>:-std=c++11>
    $<$<COMPILE_LANGUAGE:CXX>:-fpermissive>
    $<$<COMPILE_LANGUAGE:CXX>:-fno-rtti>
    $<$<COMPILE_LANGUAGE:CXX>:-fno-exceptions>
)

target_link_options(${VA_TARGET_NAME} PRIVATE
    "-Wl,-z,max-page-size=16384"
)

target_link_libraries(${VA_TARGET_NAME}
    log
    atomic
)
