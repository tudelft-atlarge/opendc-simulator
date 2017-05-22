cmake_minimum_required(VERSION 3.1)
project(opendc-simulator)
set(CMAKE_MODULE_PATH ${CMAKE_MODULE_PATH} "${CMAKE_SOURCE_DIR}/cmake/")

# Build options
option(WITH_DOCUMENTATION 
	"Use Doxygen to create API documentation of this library." OFF)

# Use system libraries
option(USE_SYSTEM_LIBRARIES
	"Use system libraries instead of the libraries bundled in this distribution." ON)

# Use C++14
set(CMAKE_CXX_STANDARD 14)

# MariaDB dependency
if (USE_SYSTEM_LIBRARIES)
	find_package(MariaDB REQUIRED)
	if (NOT MARIADB_FOUND)
		message(FATAL_ERROR "The MariaDB connector library was not found on the system") 
	endif()
else()
	message(FATAL_ERROR "The project requires system libraries for now")
	#add_library(mariadb SHARED opendc-simulator-dependencies/sqlite/sqlite3.c)
	#target_include_directories(mariadb PUBLIC opendc-simulator-dependencies/sqlite)
endif()

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
target_include_directories(simulator PUBLIC include/)
target_link_libraries(simulator mariadb)

# Testing
#enable_testing()
#add_subdirectory(opendc-simulator-dependencies/googletest)
#add_executable(simulator-tests 
#	tests/src/main.cpp	
#)
#target_include_directories(simulator-tests PRIVATE include/ tests/include)
#target_link_libraries(simulator-tests gtest gtest_main)
#add_test(simulator-tests simulator-tests)

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
