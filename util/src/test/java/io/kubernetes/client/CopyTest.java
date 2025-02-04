/*
Copyright 2020 The Kubernetes Authors.
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at
http://www.apache.org/licenses/LICENSE-2.0
Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package io.kubernetes.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import com.github.tomakehurst.wiremock.junit.WireMockRule;
import com.github.tomakehurst.wiremock.matching.AnythingPattern;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.util.ClientBuilder;
import io.kubernetes.client.util.exception.CopyNotSupportedException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Tests for the Copy helper class */
public class CopyTest {
  private String namespace;
  private String podName;
  private String[] cmd;

  private ApiClient client;

  @Rule public WireMockRule wireMockRule = new WireMockRule(wireMockConfig().dynamicPort());
  @Rule public TemporaryFolder folder = new TemporaryFolder();

  @Before
  public void setup() throws IOException {
    client = new ClientBuilder().setBasePath("http://localhost:" + wireMockRule.port()).build();

    namespace = "default";
    podName = "apod";
  }

  @Test
  public void testUrl() throws IOException, ApiException, InterruptedException {
    Copy copy = new Copy(client);

    V1Pod pod = new V1Pod().metadata(new V1ObjectMeta().name(podName).namespace(namespace));

    wireMockRule.stubFor(
        get(urlPathEqualTo("/api/v1/namespaces/" + namespace + "/pods/" + podName + "/exec"))
            .willReturn(
                aResponse()
                    .withStatus(404)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{}")));

    try {
      InputStream inputStream = copy.copyFileFromPod(pod, "container", "/some/path/to/file");
      // block until the connection is established
      inputStream.read();
      inputStream.close();
    } catch (IOException | ApiException | IllegalStateException e) {
      e.printStackTrace();
    }

    wireMockRule.verify(
        getRequestedFor(
                urlPathEqualTo("/api/v1/namespaces/" + namespace + "/pods/" + podName + "/exec"))
            .withQueryParam("stdin", equalTo("false"))
            .withQueryParam("stdout", equalTo("true"))
            .withQueryParam("stderr", equalTo("true"))
            .withQueryParam("tty", equalTo("false"))
            .withQueryParam("command", new AnythingPattern()));
  }

  @Test
  public void testCopyFileToPod() throws IOException, InterruptedException {

    File testFile = File.createTempFile("testfile", null);
    testFile.deleteOnExit();

    Copy copy = new Copy(client);

    wireMockRule.stubFor(
        get(urlPathEqualTo("/api/v1/namespaces/" + namespace + "/pods/" + podName + "/exec"))
            .willReturn(
                aResponse()
                    .withStatus(404)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{}")));

    // When attempting to write to the process outputstream in copyFileToPod, the
    // WebSocketStreamHandler is in a wait state because no websocket is created by mock, which
    // blocks the main thread. So here we execute the method in a thread.
    private final Semaphore semaphore = new Semaphore(0);
    Thread t =
        new Thread(
            new Runnable() {
              public void run() {
                try {
                  copy.copyFileToPod(
                      namespace, podName, "", testFile.toPath(), Paths.get("/copied-testfile"));
                } catch (IOException | ApiException ex) {
                  ex.printStackTrace();
                }
              }
            });
    t.start();
    var counter = new AsyncCounter(this);

    counter.startCounting();

    semaphore.acquire();
    t.interrupt();

    wireMockRule.verify(
        getRequestedFor(
                urlPathEqualTo("/api/v1/namespaces/" + namespace + "/pods/" + podName + "/exec"))
            .withQueryParam("stdin", equalTo("true"))
            .withQueryParam("stdout", equalTo("true"))
            .withQueryParam("stderr", equalTo("true"))
            .withQueryParam("tty", equalTo("false"))
            .withQueryParam("command", equalTo("sh"))
            .withQueryParam("command", equalTo("-c"))
            .withQueryParam("command", equalTo("tar -xmf - -C /")));
  }

  @Test
  public void testCopyBinaryDataToPod() throws InterruptedException {

    byte[] testSrc = new byte[0];

    Copy copy = new Copy(client);

    wireMockRule.stubFor(
        get(urlPathEqualTo("/api/v1/namespaces/" + namespace + "/pods/" + podName + "/exec"))
            .willReturn(
                aResponse()
                    .withStatus(404)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{}")));

    // When attempting to write to the process outputstream in copyFileToPod, the
    // WebSocketStreamHandler is in a wait state because no websocket is created by mock, which
    // blocks the main thread. So here we execute the method in a thread.
    Thread t =
        new Thread(
            new Runnable() {
              public void run() {
                try {
                  copy.copyFileToPod(
                      namespace, podName, "", testSrc, Paths.get("/copied-binarydata"));
                } catch (IOException | ApiException ex) {
                  ex.printStackTrace();
                }
              }
            });
    t.start();
    Thread.sleep(2000);
    t.interrupt();

    wireMockRule.verify(
        getRequestedFor(
                urlPathEqualTo("/api/v1/namespaces/" + namespace + "/pods/" + podName + "/exec"))
            .withQueryParam("stdin", equalTo("true"))
            .withQueryParam("stdout", equalTo("true"))
            .withQueryParam("stderr", equalTo("true"))
            .withQueryParam("tty", equalTo("false"))
            .withQueryParam("command", equalTo("sh"))
            .withQueryParam("command", equalTo("-c"))
            .withQueryParam("command", equalTo("tar -xmf - -C /")));
  }

  public void testCopyDirectoryFromPod() throws IOException, ApiException, InterruptedException {

    // Create a temp directory
    File tempFolder = folder.newFolder("destinationFolder");

    Copy copy = new Copy(client);

    wireMockRule.stubFor(
        get(urlPathEqualTo("/api/v1/namespaces/" + namespace + "/pods/" + podName + "/exec"))
            .willReturn(
                aResponse()
                    .withStatus(404)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{}")));

    Thread t =
        new Thread(
            new Runnable() {
              public void run() {
                try {
                  copy.copyDirectoryFromPod(
                      namespace,
                      podName,
                      "",
                      tempFolder.toPath().toString(),
                      Paths.get("/copied-testDir"));
                } catch (IOException | ApiException | CopyNotSupportedException ex) {
                  ex.printStackTrace();
                }
              }
            });
    t.start();
    Thread.sleep(2000);
    t.interrupt();

    wireMockRule.verify(
        getRequestedFor(
                urlPathEqualTo("/api/v1/namespaces/" + namespace + "/pods/" + podName + "/exec"))
            .withQueryParam("stdin", equalTo("false"))
            .withQueryParam("stdout", equalTo("true"))
            .withQueryParam("stderr", equalTo("true"))
            .withQueryParam("tty", equalTo("false"))
            .withQueryParam("command", equalTo("sh"))
            .withQueryParam("command", equalTo("-c"))
            .withQueryParam("command", equalTo("tar --version")));
  }
}
