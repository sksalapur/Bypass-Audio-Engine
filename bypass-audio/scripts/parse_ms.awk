BEGIN {
    playing = 0;
    pkg = "com.music.bitchord";
    title = "BitChord Track";
    artist = "BitChord Playback";
}

/package=/ {
    match($0, /package=[^ ]*/)
    if (RSTART > 0) {
        p = substr($0, RSTART + 8, RLENGTH - 8);
        gsub(/[\r\n"\\ ]/, "", p);
        if (p != "com.android.server.telecom" && p != "com.google.android.gms" && p != "system" && p != "") {
            pkg = p;
        }
    }
}

/state=PlaybackState \{state=3/ || /state=3/ {
    playing = 1;
}

/metadata:.*description=/ {
    match($0, /description=[^,\r\n]*/)
    if (RSTART > 0) {
        t = substr($0, RSTART + 12, RLENGTH - 12);
        gsub(/^ +| +$/, "", t);
        gsub(/[\r\n"\\]/, "", t);
        if (t != "" && t != "null") title = t;
    }
    match($0, /description=[^,]*, *[^,\r\n]*/)
    if (RSTART > 0) {
        s = substr($0, RSTART + 12, RLENGTH - 12);
        sub(/.*, */, "", s);
        gsub(/^ +| +$/, "", s);
        gsub(/[\r\n"\\]/, "", s);
        if (s != "" && s != "null" && s != title) artist = s;
    }
}

END {
    print "MS_PLAYING=" playing;
    print "MEDIA_PKG=\"" pkg "\"";
    print "MEDIA_TITLE=\"" title "\"";
    print "MEDIA_ARTIST=\"" artist "\"";
}
