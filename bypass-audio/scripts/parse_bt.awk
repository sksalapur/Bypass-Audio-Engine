BEGIN {
    connected = 0;
    streaming = 0;
    codec = (prop_codec != "") ? prop_codec : "AAC";
    dev = "Bluetooth Headset";
    rate = 44100;
    bits = 16;
    bitrate = (prop_bitrate != "") ? prop_bitrate : "328 kbps";
}

/Connected: true|ConnectionState: STATE_CONNECTED|mActiveDevice: [0-9a-fA-F:]+|StateMachine: name=A2dpStateMachine state=Connected/ {
    connected = 1;
}

/Streaming: true|EVENT_TYPE_AUDIO_STATE_CHANGED.*STARTED/ {
    streaming = 1;
}

/model_name=/ {
    match($0, /model_name=[^|)]*/)
    if (RSTART > 0 && dev == "Bluetooth Headset") {
        dev = substr($0, RSTART + 11, RLENGTH - 11);
        gsub(/[\r\n"\\]/, "", dev);
    }
}

/Bonded devices:/ {
    in_bonded = 1;
    next;
}

in_bonded && /^[ \t]*[0-9A-Fa-f:]+/ {
    if (dev == "Bluetooth Headset") {
        match($0, /\[[^]]*\][ \t]*.*/)
        if (RSTART > 0) {
            d = substr($0, RSTART, RLENGTH);
            sub(/^\[[^]]*\][ \t]*/, "", d);
            gsub(/[\r\n"\\]/, "", d);
            if (d != "") dev = d;
        }
    }
    in_bonded = 0;
}

/Current Codec:/ {
    if ($3 != "") codec = $3;
}

/mSampleRate:0x[0-9a-fA-F]*\([0-9]*\)/ {
    match($0, /mSampleRate:0x[0-9a-fA-F]*\([0-9]*\)/)
    if (RSTART > 0) {
        s = substr($0, RSTART, RLENGTH);
        sub(/.*\(/, "", s);
        sub(/\).*/, "", s);
        if (s + 0 > 0) rate = s + 0;
    }
}

/mBitsPerSample:0x[0-9a-fA-F]*\([0-9]*\)/ {
    match($0, /mBitsPerSample:0x[0-9a-fA-F]*\([0-9]*\)/)
    if (RSTART > 0) {
        s = substr($0, RSTART, RLENGTH);
        sub(/.*\(/, "", s);
        sub(/\).*/, "", s);
        if (s + 0 > 0) bits = s + 0;
    }
}

/LHDC transmission bitrate/ {
    if ($6 != "" && $6 != "-1") bitrate = $6 " kbps (ABR)";
}

END {
    if (codec ~ /^LHDC/) {
        if (codec == "LHDC_V2") codec = "LHDC Version 2";
        else codec = "LHDC Version 5";
    } else if (codec ~ /LDAC/ && bitrate == "328 kbps") {
        bitrate = "990 kbps";
    } else if (codec ~ /AAC/ && bitrate == "328 kbps") {
        bitrate = "256 kbps";
    }

    print "BT_CONNECTED=" connected;
    print "BT_STREAMING=" streaming;
    print "BT_CODEC=\"" codec "\"";
    print "BT_DEVICE=\"" dev "\"";
    print "BT_RATE=" rate;
    print "BT_BITS=" bits;
    print "BT_BITRATE=\"" bitrate "\"";
}

