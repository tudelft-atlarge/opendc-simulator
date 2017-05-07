cmake_minimum_required(VERSION 3.1)
project(opendc-simulator)

# Build options
option(WITH_DOCUMENTATION 
	"Use Doxygen to create API documentation of this library." OFF)

# Use C++14
set(CMAKE_CXX_STANDARD 14)

# sqlite3 dependency
add_library(sqlite3 STATIC opendc-simulator-dependencies/sqlite/sqlite3.c)
target_include_directories(sqlite3 PUBLIC opendc-simulator-dependencies/sqlite)

add_executable(simulator 
	src/Simulator.cpp
	src/database/Database.cpp
	src/simulation/workloads/Workload.cpp
	src/simulation/workloads/WorkloadPool.cpp
	src/modeling/machine/CPU.cpp
	src/modeling/machine/GPU.cpp
	src/modeling/machine/Machine.cpp
	src/modeling/Entity.cpp
	src/modeling/Rack.cpp
)
target_compile_options(simulator PRIVATE -std=c++14)
target_include_directories(simulator PUBLIC include/)
target_link_libraries(simulator sqlite3)

# Testing
enable_testing()
add_subdirectory(opendc-simulator-dependencies/googletest)
add_executable(simulator-tests 
	tests/src/main.cpp	
)
target_include_directories(simulator-tests PRIVATE include/ tests/include)
target_link_libraries(simulator-tests gtest gtest_main)
add_test(simulator-tests simulator-tests)

# Documentation
if(WITH_DOCUMENTATION)
	find_package(Doxygen)
	if (NOT DOXYGEN_FOUND)
		message(FATAL_ERROR
		"Doxygen is needed to build the documentation. Please install it correctly")
	endif()
	add_custom_target(docs ALL
		COMMAND ${DOXYGEN_EXECUTABLE} ${PROJECT_SOURCE_DIR}/Doxyfile
		SOURCES ${PROJECT_SOURCE_DIR}/Doxyfile
		WORKING_DIRECTORY ${PROJECT_SOURCE_DIR}
	)
endif()
