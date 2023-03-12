(ns blobcas.core
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [ring.adapter.jetty :refer [run-jetty]])
  (:import [java.io OutputStream File FileNotFoundException]
           [java.security DigestInputStream MessageDigest SecureRandom])
  (:gen-class))

(def storage-path (System/getenv "STORAGE_PATH"))
(def keep-all-versions?
  (or (some-> (System/getenv "KEEP_ALL_VERSIONS") Boolean/parseBoolean) false))
(def max-blob-size (* 1 1024 1024))
(def blob-id-length 25)
(def random (SecureRandom.))
(def empty-input-sha256 "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")

(defn generate-base62 []
  (lazy-seq (cons (.nextInt random 62) (generate-base62))))

(defn generate-key [length]
  (let [alphabet "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"]
    (apply str (map (partial nth alphabet) (take length (generate-base62))))))

(def session-admin-key (generate-key 32))

(defn generate-blob-id []
  (generate-key blob-id-length))

(defn blob-path [blob-id]
  (str storage-path \/ blob-id))

(defn valid-blob-id? [id]
  (and (= (count id) blob-id-length)
       (boolean (re-matches #"[0-9a-zA-Z]*" id))
       (.exists (io/file (blob-path id)))))

(defn bytes->hex [bs]
  (apply str (map #(format "%02x" %) bs)))

(defn sha256 [file]
  (try
    (with-open [in (io/input-stream file)
                out (OutputStream/nullOutputStream)]
      (let [digest-in (DigestInputStream. in (MessageDigest/getInstance "SHA256"))]
        (.transferTo digest-in out)
        (bytes->hex (.digest (.getMessageDigest digest-in)))))
    (catch FileNotFoundException _
      empty-input-sha256)))

(defn store-blob! [blob-id data size replaces]
  (cond
    (nil? size) :need-size
    (< max-blob-size size) :too-large
    ;; real check happens later; this is just to fail early and avoid large uploads in vain
    (not= replaces (sha256 (blob-path blob-id))) :conflict
    :else
    (let [f (File/createTempFile (str blob-id "-") ".partial" (File. storage-path))]
      (try
        (with-open [out (io/output-stream f)]
          (.transferTo data out))
        (locking storage-path
          (let [file (blob-path blob-id)]
            (if-not (= replaces (sha256 file))
              :conflict
              (do
                (when keep-all-versions?
                  (.renameTo
                    (File. file)
                    (File. (str file \. (System/currentTimeMillis)))))
                (.renameTo f (File. file))
                :ok))))
        (finally
          (when (.exists f)
            (.delete f)))))))

(defn text-response [status message]
  {:status status
   :headers {"Content-type" "text/plain"}
   :body (str message \newline)})

(defn not-found []
  (text-response 404 "Not here"))

(defn get-blob [blob-id]
  {:body (io/input-stream (blob-path blob-id))
   :headers {"Content-type" "application/octet-stream"}})

(defn put-blob! [blob-id data size replaces]
  (case (store-blob! blob-id data size replaces)
    :need-size (text-response 400 "Chunked transfer not supported")
    :conflict (text-response 409 "Conflict")
    :too-large (text-response 413 "Too much")
    :ok (text-response 200 "Stored")))

(defn post-blob! [data size admin-key]
  (if (not= admin-key session-admin-key)
    (text-response 403 "Nope")
    (let [blob-id (generate-blob-id)
          response (put-blob! blob-id data size empty-input-sha256)]
      (if (= 200 (:status response))
        (assoc response
               :status 201
               :headers (assoc (:headers response) "location" (str "/" blob-id))
               :body (str "Created " blob-id))
        response))))

(defn strip-prefix [s prefix]
  (if (string/starts-with? s prefix)
    (subs s (count prefix))
    s))

(defn handler [request]
  (let [size (some-> request :headers (get "content-length") (Long/parseLong))]
    (if (and (= (:request-method request) :post)
             (= (:uri request) "/"))
      (post-blob!
        (:body request)
        size
        (strip-prefix (or (:query-string request) "") "admin-key="))
      (let [blob-id (subs (:uri request) 1)]
        (if (valid-blob-id? blob-id)
          (case (:request-method request)
            :get (get-blob blob-id)
            :put (put-blob!
                   blob-id
                   (:body request)
                   size
                   (strip-prefix (or (:query-string request) "") "replaces="))
            (not-found))
          (not-found))))))

(defn -main []
  (println " *** admin key for this session is" session-admin-key)
  (run-jetty handler {:port 8080 :send-server-version? false}))

(comment
  ;; evaluate this to start the development server
  (do
    (require '[ring.middleware.reload :refer [wrap-reload]])
    (run-jetty (wrap-reload #'handler) {:port 4712 :join? false})))
