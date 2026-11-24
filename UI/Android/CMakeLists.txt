add_library(ladybird SHARED
    src/main/cpp/LadybirdActivity.cpp
    src/main/cpp/LadybirdHub.cpp
    src/main/cpp/ALooperEventLoopImplementation.cpp
    src/main/cpp/TimerExecutorService.cpp
    src/main/cpp/JNIHelpers.cpp
)
target_include_directories(ladybird PRIVATE ${CMAKE_CURRENT_BINARY_DIR}/../.. ${LADYBIRD_SOURCE_DIR}/Services)
# JNI entry points are found by name at run time, so they have no declarations in a header.
target_compile_options(ladybird PRIVATE -Wno-missing-prototypes)
target_link_libraries(ladybird PRIVATE LibFileSystem LibGfx LibURL jnigraphics android nativewindow log)

include(../cmake/AndroidExtras.cmake)
create_ladybird_bundle(ladybird)

# An APK can only carry executables as libraries in its native library directory, where Android unpacks them with
# execute permission. So each helper process ships as lib<Name>.so, and the UI spawns them from there.
set(helper_jni_dir "${LADYBIRD_SOURCE_DIR}/UI/Android/src/main/jniLibs/${ANDROID_ABI}")
set(copy_helper_commands COMMAND ${CMAKE_COMMAND} -E make_directory "${helper_jni_dir}")
foreach (helper IN LISTS ladybird_helper_processes)
    if (TARGET ${helper})
        list(APPEND copy_helper_commands COMMAND ${CMAKE_COMMAND} -E copy_if_different "$<TARGET_FILE:${helper}>" "${helper_jni_dir}/lib${helper}.so")
        list(APPEND copied_helpers ${helper})
    endif()
endforeach()
add_custom_target(copy-helper-processes ALL ${copy_helper_commands} DEPENDS ${copied_helpers} VERBATIM)
add_dependencies(ladybird copy-helper-processes)
