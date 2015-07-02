#!/bin/bash

IP="$1"

export MODACLOUDS_TOWER4CLOUDS_MANAGER_IP=$IP

rm /var/lib/tomcat7/logs/*

service tomcat7 start
