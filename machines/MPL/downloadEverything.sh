#!/bin/bash

BASE="https://raw.githubusercontent.com/deib-polimi/modaclouds-tests/master/machines/MPL"
FOLDER="https://github.com/deib-polimi/modaclouds-tests/tree/master/machines/MPL"

for file in $(curl -s $FOLDER |
                  grep href |
                  grep blob |
                  sed 's/.*href="//' |
                  sed 's/".*//' |
                  sed 's|.*/||'); do
    curl "$BASE"/"$file" -O -L
done
