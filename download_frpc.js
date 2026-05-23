const https = require('https');
const fs = require('fs');
const zlib = require('zlib');
const tar = require('tar-stream');
const path = require('path');

const url = "https://github.com/fatedier/frp/releases/download/v0.61.1/frp_0.61.1_linux_arm64.tar.gz";

const dir = path.join(__dirname, 'app/src/main/jniLibs/arm64-v8a');
fs.mkdirSync(dir, { recursive: true });

https.get(url, (res) => {
    if (res.statusCode === 301 || res.statusCode === 302) {
        https.get(res.headers.location, processStream);
    } else {
        processStream(res);
    }
});

function processStream(res) {
    const extract = tar.extract();
    
    extract.on('entry', function(header, stream, next) {
        if (header.name.endsWith('/frpc')) {
            const outStream = fs.createWriteStream(path.join(dir, 'libfrpc.so'));
            stream.pipe(outStream);
            stream.on('end', function() {
                next();
            });
        } else {
            stream.on('end', function() {
                next();
            });
            stream.resume();
        }
    });

    extract.on('finish', function() {
        console.log('flpc downloaded and extracted to libflpc.so!');
    });

    res.pipe(zlib.createGunzip()).pipe(extract);
}
