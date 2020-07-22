#!/bin/bash
# example usages:
# ./compile.sh - package Spark without assembly modules (much faster)
# ./compile.sh clean install - package install of Spark without assembly modules
# ./compile.sh full - package everything ("normal" compilation)
# ./compile.sh clean full install - package and install everything ("normal" clean install)

SCRIPT_DIR=$(cd $(dirname $(readlink -f $0 || echo $0));pwd -P)
source $SCRIPT_DIR/args.sh
echo $DIR
cd $DIR

if [[ "$1" == "clean" ]]; then
    CLEAN_ARGS=clean
    shift 1
fi

if [[ !("$@" =~ "-pl") ]]; then
    if [[ "$1" == "full" ]]; then
	# used to compile everything
	SKIP_MODULES=''
	shift 1
    elif [[ "$1" == "main" ]]; then
	# used to compile main modules including assembly, which is used to launch spark externally
	SKIP_MODULES='-pl !examples,!external/flume-assembly,!external/kafka-assembly,!external/mqtt,!external/mqtt-assembly'
	shift 1
    else
	# used to compile minimum set to be able to run all tests
	# this is because assemblies and examples take a lot of time to compile
	SKIP_MODULES='-pl !examples,!assembly,!external/flume-assembly,!external/kafka-assembly,!external/mqtt,!external/mqtt-assembly'
    fi
fi
echo "$MVN_CMD -T $MVN_COMPILE_PARALLEL_THREADS $MVN_ARGS $SKIP_MODULES -DskipTests $CLEAN_ARGS package $@"
$MVN_CMD -T $MVN_COMPILE_PARALLEL_THREADS $MVN_ARGS $SKIP_MODULES -DskipTests $CLEAN_ARGS package $@ 2>&1 | tee ~/compile.txt
killZinc
