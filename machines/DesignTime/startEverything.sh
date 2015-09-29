#!/bin/sh

docker run --name designtime \
-p 5901:5901 \
-p 3389:3389 \
-v /home/ubuntu/space4clouds/modaclouds-models:/opt/space4clouds \
-v /home/ubuntu/modelio:/opt/modelio \
-d \
deibpolimi/modaclouds-designtime
