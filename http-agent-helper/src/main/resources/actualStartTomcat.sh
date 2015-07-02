#!/bin/bash

IP="$1"

export MODACLOUDS_TOWER4CLOUDS_MANAGER_IP=$IP
service tomcat7 start
