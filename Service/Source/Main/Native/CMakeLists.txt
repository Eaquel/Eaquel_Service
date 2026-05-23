cmake_minimum_required(VERSION 4.3.2)
project("gamecore" CXX)

set(CMAKE_CXX_STANDARD 26)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} \
    -fvisibility=hidden \
    -fstack-protector-all \
    -D_FORTIFY_SOURCE=3 \
    -Wformat -Wformat-security \
    -Wall -Wno-unused-parameter")

set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} \
    -O3 -ffunction-sections -fdata-sections -flto")

find_library(log-lib     log)
find_library(android-lib android)
find_library(dl-lib      dl)

add_library(gamecore SHARED
    Engine.cpp
)

target_include_directories(gamecore PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

target_link_libraries(gamecore
    ${log-lib}
    ${android-lib}
    ${dl-lib}
    "-Wl,--gc-sections"
    "-Wl,-z,relro,-z,now"
)
