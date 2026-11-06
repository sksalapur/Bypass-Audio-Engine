BEGIN {
    rate = 0;
    container = "FLAC / WAV";
}

/sampleRate=[0-9]*/ {
    match($0, /sampleRate=[0-9]*/)
    if (RSTART > 0) {
        s = substr($0, RSTART + 11, RLENGTH - 11);
        if (s + 0 > 0) rate = s + 0;
    }
}

/sample_rate:[0-9]*/ {
    match($0, /sample_rate:[0-9]*/)
    if (RSTART > 0) {
        s = substr($0, RSTART + 12, RLENGTH - 12);
        if (s + 0 > 0) rate = s + 0;
    }
}

/[Ff][Ll][Aa][Cc]/ { container = "FLAC"; }
/[Oo][Pp][Uu][Ss]/ { container = "Opus"; }
/[Aa][Aa][Cc]/ { container = "AAC"; }
/[Aa][Ll][Aa][Cc]/ { container = "ALAC"; }
/[Mm][Pp]3/ { container = "MP3"; }

END {
    print "METRICS_RATE=" rate;
    print "METRICS_CONTAINER=\"" container "\"";
}
