(ns climate-changed.backend.middleware-test
  (:require [climate-changed.backend.middleware :as mw]
            [clojure.test :refer [deftest is testing]])
  (:import [java.io ByteArrayInputStream]
           [java.util.zip GZIPInputStream]))

(defn- gunzip
  "Decompress a gzip byte array back to its UTF-8 string."
  [^bytes b]
  (with-open [in (GZIPInputStream. (ByteArrayInputStream. b))]
    (slurp in)))

(deftest client-ip-test
  (let [client-ip #'mw/client-ip]
    (testing "prefers CloudFront-Viewer-Address"
      (is (= "1.2.3.4"
             (client-ip {:headers     {"cloudfront-viewer-address" "1.2.3.4:5678"}
                         :remote-addr "10.0.0.1"}))))
    (testing "falls back to the last X-Forwarded-For hop"
      (is (= "9.8.7.6"
             (client-ip {:headers     {"x-forwarded-for" "1.1.1.1, 2.2.2.2, 9.8.7.6"}
                         :remote-addr "10.0.0.1"}))))
    (testing "falls back to Ring's remote-addr"
      (is (= "10.0.0.1"
             (client-ip {:headers     {}
                         :remote-addr "10.0.0.1"}))))))

(deftest wrap-edn-response-test
  (testing "serialises collection bodies to EDN"
    (let [handler (fn [_] {:status 200 :body {:a 1 :b [1 2]}})
          resp    ((mw/wrap-edn-response handler) {})]
      (is (= "application/edn" (get-in resp [:headers "Content-Type"])))
      (is (= "{:a 1, :b [1 2]}" (:body resp)))))
  (testing "leaves string bodies untouched"
    (let [handler (fn [_] {:status 200 :body "hello"})
          resp    ((mw/wrap-edn-response handler) {})]
      (is (= "hello" (:body resp)))
      (is (nil? (get-in resp [:headers "Content-Type"]))))))

(deftest wrap-gzip-middleware-test
  (let [big-body (apply str (repeat 600 "x"))
        wrapped  (mw/wrap-gzip-middleware (constantly {:status  200
                                                       :headers {}
                                                       :body    big-body}))]
    (testing "gzip-compresses accepted string bodies"
      (let [resp (wrapped {:request-method :get
                           :headers        {"accept-encoding" "gzip"}})]
        (is (= "gzip" (get-in resp [:headers "Content-Encoding"])))
        (is (= "Accept-Encoding" (get-in resp [:headers "Vary"])))
        (is (= big-body (gunzip (:body resp))))))
    (testing "skips when the client does not accept gzip"
      (let [resp (wrapped {:request-method :get :headers {}})]
        (is (= big-body (:body resp)))
        (is (nil? (get-in resp [:headers "Content-Encoding"])))))
    (testing "skips HEAD requests"
      (let [resp (wrapped {:request-method :head
                           :headers        {"accept-encoding" "gzip"}})]
        (is (= big-body (:body resp)))))
    (testing "skips non-200 responses"
      (let [resp ((mw/wrap-gzip-middleware (constantly {:status  404
                                                        :headers {}
                                                        :body    big-body}))
                  {:request-method :get :headers {"accept-encoding" "gzip"}})]
        (is (= big-body (:body resp)))))
    (testing "skips already-encoded responses"
      (let [resp ((mw/wrap-gzip-middleware (constantly {:status  200
                                                        :headers {"Content-Encoding" "br"}
                                                        :body    big-body}))
                  {:request-method :get :headers {"accept-encoding" "gzip"}})]
        (is (= big-body (:body resp)))))
    (testing "skips bodies at or below the minimum gzip size"
      (let [resp ((mw/wrap-gzip-middleware (constantly {:status  200
                                                        :headers {}
                                                        :body    "hi"}))
                  {:request-method :get :headers {"accept-encoding" "gzip"}})]
        (is (= "hi" (:body resp)))))))

;; future ideas:
;; - gzip-bytes: gunzip round-trip for string / InputStream / File / byte[] bodies.
;; - wrap-security-headers / wrap-referrer-policy: assert HSTS, X-Frame-Options DENY,
;;   X-Content-Type-Options nosniff, and Referrer-Policy.
