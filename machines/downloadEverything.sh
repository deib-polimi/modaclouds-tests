#!/bin/bash
#$1=machine name

if [ ! "$#" -gt 0 ]
then
    echo "You need to provide the machine name as the parameter of the script!"
    exit -1
fi

BASE="https://raw.githubusercontent.com/deib-polimi/modaclouds-tests/master/machines/$1"
FOLDER="https://github.com/deib-polimi/modaclouds-tests/tree/master/machines/$1"
THIS_FILE="downloadEverything.sh"

i=0
j=0
MAX_ATTEMPTS=5

for file in $(curl -s $FOLDER |
                  grep href |
                  grep blob |
                  sed 's/.*href="//' |
                  sed 's/".*//' |
                  sed 's|.*/||'); do
    if [ $file != $THIS_FILE ]; then
        let j=0
        echo "Downloading $file..."
        while [ $j -lt $MAX_ATTEMPTS ]; do
            curl "$BASE"/"$file" -O -L
            let j+=1
            if grep -q 'Error 503 backend read error' $file || grep -q 'We had issues producing the response to your request' $file
            then
                echo "The file is broken! Trying again in 10 seconds..."
                sleep 10
            else
                let j=$MAX_ATTEMPTS
            fi
        done
        let i+=1
    fi
done

echo "Done! Downloaded $i files."
