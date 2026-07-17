# The following lines of boilerplate have to be in your project's
# CMakeLists in this exact order for cmake to work correctly
cmake_minimum_required(VERSION 3.5)

set(SMART_POT_SDKCONFIG_DEFAULTS
        "${CMAKE_CURRENT_LIST_DIR}/sdkconfig.defaults"
)
set(SMART_POT_SECRETS_CONFIG "${CMAKE_CURRENT_LIST_DIR}/sdkconfig.secrets")
if(EXISTS "${SMART_POT_SECRETS_CONFIG}")
    list(APPEND SMART_POT_SDKCONFIG_DEFAULTS "${SMART_POT_SECRETS_CONFIG}")
endif()
set(SDKCONFIG_DEFAULTS "${SMART_POT_SDKCONFIG_DEFAULTS}")

include($ENV{IDF_PATH}/tools/cmake/project.cmake)

add_compile_options(-Wno-format)
project(smart_pot_esp32p4)
