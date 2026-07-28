cmake_minimum_required(VERSION 3.22.1)
project("nescore-stub" C CXX)

set(CMAKE_CXX_STANDARD 17)

add_library(nescore SHARED src/fceumm_stub.cpp)
target_link_libraries(nescore log android)
# AGP's externalNativeBuild picks up the .so automatically.
