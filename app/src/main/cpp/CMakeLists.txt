# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html

# Sets the minimum version of CMake required to build the native library.

cmake_minimum_required(VERSION 3.18.1)

add_link_options("-Wl,--build-id=none")

# Declares and names the project.

project("rattlegram")

# Creates and names a library, sets it as either STATIC
# or SHARED, and provides the relative paths to its source code.
# You can define multiple libraries, and CMake builds them for you.
# Gradle automatically packages shared libraries with your APK.

add_library( # Sets the name of the library.
        rattlegram

        # Sets the library as a shared library.
        SHARED

        # Provides a relative path to your source file(s).
        native-lib.cpp
        # Bluetooth radio audio: JNI bridge + google/libsbc (Apache-2.0, see sbc/LICENSE)
        radio-sbc.c
        sbc/sbc.c
        sbc/bits.c)

target_include_directories(rattlegram PRIVATE sbc)

# Searches for a specified prebuilt library and stores the path as a
# variable. Because CMake includes system libraries in the search path by
# default, you only need to specify the name of the public NDK library
# you want to add. CMake verifies that the library exists before
# completing its build.

find_library( # Sets the name of the path variable.
        log-lib

        # Specifies the name of the NDK library that
        # you want CMake to locate.
        log)

# Specifies libraries CMake should link to your target library. You
# can link multiple libraries, such as libraries you define in this
# build script, prebuilt third-party libraries, or system libraries.

target_link_libraries( # Specifies the target library.
        rattlegram

        # Links the target library to the log library
        # included in the NDK.
        ${log-lib})

# Optional Hamlib rig control (CAT frequency and PTT): its own library, loaded only when the user turns
# Hamlib on. libhamlib.so (LGPL-2.1-or-later) is built by hamlib/build-hamlib-android.sh into jniLibs; ABIs
# without it (32-bit x86) simply have no rig control.
set(HAMLIB_SO ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libhamlib.so)
if (EXISTS ${HAMLIB_SO})
    add_library(hamlib SHARED IMPORTED)
    set_target_properties(hamlib PROPERTIES IMPORTED_LOCATION ${HAMLIB_SO})
    add_library(rattlegram-rig SHARED rig-hamlib.c)
    target_include_directories(rattlegram-rig PRIVATE hamlib/include)
    target_link_libraries(rattlegram-rig hamlib ${log-lib})
endif ()
