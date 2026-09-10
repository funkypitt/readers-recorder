# Distributed under the OSI-approved BSD 3-Clause License.  See accompanying
# file Copyright.txt or https://cmake.org/licensing for details.

cmake_minimum_required(VERSION 3.22.1-g37088a8)

# We name the project and the target for the ExternalProject_Add() call
# to something that will highlight to the user what we are working on if
# something goes wrong and an error message is produced.

project(ggml_whisper-populate NONE)



include(ExternalProject)
ExternalProject_Add(ggml_whisper-populate
                     "UPDATE_DISCONNECTED" "False"
                    SOURCE_DIR          "/home/freedomfighter/code/readers-recorder/app/src/main/cpp/whisper.cpp/ggml"
                    BINARY_DIR          "/home/freedomfighter/code/readers-recorder/app/.cxx/Debug/613t3z3f/x86_64/ggml_whisper"
                    CONFIGURE_COMMAND   ""
                    BUILD_COMMAND       ""
                    INSTALL_COMMAND     ""
                    TEST_COMMAND        ""
                    USES_TERMINAL_DOWNLOAD  YES
                    USES_TERMINAL_UPDATE    YES
)


