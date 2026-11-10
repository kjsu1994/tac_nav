cmake_minimum_required(VERSION 3.22.1)
project(tacnav_wmm C)
add_library(tacnav_wmm SHARED geomag.c wmm_jni.c)
target_link_libraries(tacnav_wmm m)
