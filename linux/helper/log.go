package main

import (
	"fmt"
	"os"
	"sync"
	"time"
)

var logMu sync.Mutex

// Off unless the user turns it on in the app: the log would record servers, routes and DNS.
func debugLogEnabled() bool {
	_, err := os.Stat(debugLogFlag)
	return err == nil
}

func log(format string, args ...any) {
	if !debugLogEnabled() {
		return
	}
	logMu.Lock()
	defer logMu.Unlock()
	f, err := os.OpenFile(logPath, os.O_WRONLY|os.O_APPEND|os.O_CREATE, 0o600)
	if err != nil {
		return
	}
	defer f.Close()
	fmt.Fprintf(f, "[%s] %s\n", time.Now().Format("2006-01-02 15:04:05.000"), fmt.Sprintf(format, args...))
}

func setDebugLog(enabled bool) {
	if enabled {
		_ = os.WriteFile(debugLogFlag, nil, 0o600)
		log("debug log enabled")
		return
	}
	_ = os.Remove(debugLogFlag)
	_ = os.Remove(logPath)
}
