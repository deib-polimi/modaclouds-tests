#!/bin/bash
#$1=DB-IP
#$2=MP-IP

bash /home/ubuntu/stopImperialDC

cd ~/glassfish4/bin/
sudo ./asadmin stop-domain domain1

cd
