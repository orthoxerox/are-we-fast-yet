#!/bin/bash

source ./script.inc
source ./config.inc

INFO Build JRuby Truffle-head
cd JRuby
./mvnw clean
./mvnw

OK JRuby Build Completed.
