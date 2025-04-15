#!/usr/bin/env bash

rm -f lb-bin.zip web-server.zip

cd loadbalancer
if mvn -e clean install; then
    cd ..

    cd web-server
    if mvn -e clean install; then
        cd ..

        cp -r loadbalancer/target/classes lb-bin
        zip -r lb-bin.zip lb-bin
        rm -rf lb-bin

        cp -r web-server/target/classes ws-bin
        zip -r web-server.zip ws-bin
        rm -rf ws-bin

        echo "Build and packaging complete."
    else
        cd ..
        echo "Failed to build web-server"
    fi
else
    cd ..
    echo "Failed to build loadbalancer"
fi
