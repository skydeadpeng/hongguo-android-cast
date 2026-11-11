package auth

import (
	"encoding/pem"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
)

// Optional cross-language proof uses the actual Android transport and state
// reducer on the JVM against this real TLS Go API. It needs a local SDK and JSON
// runtime; ordinary server-only test/build remains independent of Android tools.
func TestAndroidHTTPSIntegration(t *testing.T) {
	root := os.Getenv("HONGGUO_ANDROID_SOURCE_ROOT")
	if root == "" {
		t.Skip("set HONGGUO_ANDROID_SOURCE_ROOT for Android/Go TLS integration")
	}
	javaHome := os.Getenv("JAVA_HOME")
	if javaHome == "" {
		t.Fatal("JAVA_HOME is required")
	}
	sdk := os.Getenv("ANDROID_HOME")
	if sdk == "" {
		t.Fatal("ANDROID_HOME is required")
	}
	jsonJar := os.Getenv("JSON_JAR")
	if jsonJar == "" {
		t.Fatal("JSON_JAR is required")
	}
	platform := filepath.Join(sdk, "platforms", "android-35", "android.jar")
	output := t.TempDir()
	source := filepath.Join(root, "android", "app", "src", "main", "java", "io", "github", "jackotom", "hongguo", "android")
	args := []string{"--release", "17", "-encoding", "UTF-8", "-cp", jsonJar + string(os.PathListSeparator) + platform, "-d", output}
	for _, name := range []string{"AccountSync.java", "AccountTokenVault.java", "AccountEndpoint.java", "HistorySyncState.java", "LibraryStore.java"} {
		args = append(args, filepath.Join(source, name))
	}
	args = append(args, filepath.Join("testdata", "AndroidHTTPSIntegration.java"))
	compile := exec.Command(filepath.Join(javaHome, "bin", "javac"), args...)
	if data, err := compile.CombinedOutput(); err != nil {
		t.Fatalf("javac failed: %v\n%s", err, data)
	}
	service, err := NewServer(Config{DBPath: filepath.Join(t.TempDir(), "login.sqlite")})
	if err != nil {
		t.Fatal(err)
	}
	defer service.Close()
	createTestUser(t, service, "alice")
	createTestUser(t, service, "bob")
	var redirectTargetHits atomic.Int64
	redirectTarget := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { redirectTargetHits.Add(1); w.WriteHeader(200) }))
	defer redirectTarget.Close()
	endpoint := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/probe-redirect" {
			http.Redirect(w, r, redirectTarget.URL, http.StatusFound)
			return
		}
		service.Handler().ServeHTTP(w, r)
	}))
	defer endpoint.Close()
	certificate := filepath.Join(output, "fixture.pem")
	if err = os.WriteFile(certificate, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: endpoint.Certificate().Raw}), 0600); err != nil {
		t.Fatal(err)
	}
	classpath := strings.Join([]string{output, jsonJar, platform}, string(os.PathListSeparator))
	run := exec.Command(filepath.Join(javaHome, "bin", "java"), "-cp", classpath, "io.github.jackotom.hongguo.android.AndroidHTTPSIntegration", endpoint.URL, certificate)
	data, err := run.CombinedOutput()
	if err != nil {
		t.Fatalf("Android transport/Go TLS test failed: %v\n%s", err, data)
	}
	t.Log(strings.TrimSpace(string(data)))
	if !strings.Contains(string(data), "ANDROID_GO_HTTPS_INTEGRATION_PASS") {
		t.Fatalf("missing completion marker: %s", data)
	}
	if redirectTargetHits.Load() != 0 {
		t.Fatal("client forwarded bearer request across redirect")
	}
}
