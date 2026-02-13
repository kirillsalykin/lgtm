#!/usr/bin/env bb

(require '[babashka.http-client :as http]
         '[cheshire.core :as json]
         '[org.httpkit.server :as server])

(def service-name (System/getenv "SERVICE_NAME"))
(def port (parse-long (or (System/getenv "PORT") "8080")))
(def downstream-urls (some-> (System/getenv "DOWNSTREAM_URLS")
                             not-empty
                             (clojure.string/split #",")))
(def otlp-endpoint (System/getenv "OTEL_EXPORTER_OTLP_ENDPOINT"))

(def quotes
  ["The only way to do great work is to love what you do. - Steve Jobs"
   "Innovation distinguishes between a leader and a follower. - Steve Jobs"
   "Stay hungry, stay foolish. - Steve Jobs"
   "Simplicity is the ultimate sophistication. - Leonardo da Vinci"
   "The best way to predict the future is to invent it. - Alan Kay"
   "Talk is cheap. Show me the code. - Linus Torvalds"
   "First, solve the problem. Then, write the code. - John Johnson"
   "Code is like humor. When you have to explain it, it's bad. - Cory House"
   "Fix the cause, not the symptom. - Steve Maguire"
   "Optimism is an occupational hazard of programming. - Kent Beck"
   "The computer was born to solve problems that did not exist before. - Bill Gates"
   "Measuring programming progress by lines of code is like measuring aircraft progress by weight. - Bill Gates"
   "The best error message is the one that never shows up. - Thomas Fuchs"
   "One of my most productive days was throwing away 1000 lines of code. - Ken Thompson"
   "Deleted code is debugged code. - Jeff Sickel"
   "If debugging is the process of removing bugs, then programming must be the process of putting them in. - Edsger Dijkstra"
   "The most disastrous thing you can learn is your first programming language. - Alan Kay"
   "Programming is not about typing, it's about thinking. - Rich Hickey"
   "Simplicity is prerequisite for reliability. - Edsger Dijkstra"
   "Make it work, make it right, make it fast. - Kent Beck"])

(defn random-quote []
  (nth quotes (rand-int (count quotes))))

;; Metrics counters
(def request-count (atom 0))
(def error-count (atom 0))
(def downstream-call-count (atom 0))

(defn hex-string [n len]
  (let [s (Long/toHexString n)]
    (str (apply str (repeat (- len (count s)) "0")) s)))

(defn generate-trace-id []
  (str (hex-string (rand-int Integer/MAX_VALUE) 16)
       (hex-string (rand-int Integer/MAX_VALUE) 16)))

(defn generate-span-id []
  (hex-string (rand-int Integer/MAX_VALUE) 16))

(defn current-time-nanos []
  (* (System/currentTimeMillis) 1000000))

(defn send-trace [trace-id parent-span-id span-id span-name start-time end-time downstream-service kind]
  (when otlp-endpoint
    (let [trace-data {:resourceSpans
                      [{:resource {:attributes [{:key "service.name"
                                                 :value {:stringValue service-name}}]}
                        :scopeSpans
                        [{:scope {:name "bb-service"}
                          :spans
                          [{:traceId trace-id
                            :spanId span-id
                            :parentSpanId (or parent-span-id "")
                            :name span-name
                            :kind kind
                            :startTimeUnixNano start-time
                            :endTimeUnixNano end-time
                            :attributes (cond-> []
                                          downstream-service
                                          (conj {:key "peer.service"
                                                 :value {:stringValue downstream-service}}))}]}]}]}]
      (try
        (http/post (str otlp-endpoint "/v1/traces")
                   {:headers {"Content-Type" "application/json"}
                    :body (json/generate-string trace-data)
                    :timeout 1000})
        (catch Exception e
          (println "Failed to send trace:" (.getMessage e)))))))

(defn call-downstream [url trace-id parent-span-id]
  (swap! downstream-call-count inc)
  (let [span-id (generate-span-id)
        start-time (current-time-nanos)
        downstream-service (second (re-find #"http://([^:/]+)" url))]
    (try
      (let [response (http/get url {:headers {"X-Trace-Id" trace-id
                                              "X-Parent-Span-Id" span-id}
                                    :timeout 5000})]
        (send-trace trace-id parent-span-id span-id
                    (str "GET " downstream-service)
                    start-time (current-time-nanos)
                    downstream-service
                    3) ; SPAN_KIND_CLIENT
        {:service downstream-service :status (:status response)})
      (catch Exception e
        (swap! error-count inc)
        (send-trace trace-id parent-span-id span-id
                    (str "GET " downstream-service " (error)")
                    start-time (current-time-nanos)
                    downstream-service
                    3)
        {:service downstream-service :error (.getMessage e)}))))

(defn metrics-handler [_req]
  {:status 200
   :headers {"Content-Type" "text/plain"}
   :body (str "# HELP bb_requests_total Total number of requests handled\n"
              "# TYPE bb_requests_total counter\n"
              "bb_requests_total{service=\"" service-name "\"} " @request-count "\n"
              "# HELP bb_errors_total Total number of errors\n"
              "# TYPE bb_errors_total counter\n"
              "bb_errors_total{service=\"" service-name "\"} " @error-count "\n"
              "# HELP bb_downstream_calls_total Total number of downstream calls\n"
              "# TYPE bb_downstream_calls_total counter\n"
              "bb_downstream_calls_total{service=\"" service-name "\"} " @downstream-call-count "\n")})

(defn request-handler [req]
  (swap! request-count inc)
  (let [trace-id (or (get-in req [:headers "x-trace-id"]) (generate-trace-id))
        parent-span-id (get-in req [:headers "x-parent-span-id"])
        span-id (generate-span-id)
        start-time (current-time-nanos)
        quote (random-quote)

        ;; Log the request with a random quote
        _ (println (json/generate-string {:level "info"
                                          :service service-name
                                          :trace_id trace-id
                                          :span_id span-id
                                          :msg "Handling request"
                                          :path (:uri req)
                                          :quote quote}))

        ;; Call downstream services in parallel
        downstream-results (when (seq downstream-urls)
                             (mapv #(call-downstream % trace-id span-id) downstream-urls))

        response {:service service-name
                  :trace-id trace-id
                  :quote quote
                  :downstream downstream-results}]

    ;; Log completion
    (println (json/generate-string {:level "info"
                                    :service service-name
                                    :trace_id trace-id
                                    :span_id span-id
                                    :msg "Request completed"
                                    :downstream-count (count downstream-results)}))

    ;; Send our server span
    (send-trace trace-id parent-span-id span-id
                (str service-name " " (:uri req))
                start-time (current-time-nanos)
                nil
                2) ; SPAN_KIND_SERVER

    {:status 200
     :headers {"Content-Type" "application/json"
               "X-Trace-Id" trace-id}
     :body (json/generate-string response)}))

(defn handler [req]
  (case (:uri req)
    "/metrics" (metrics-handler req)
    (request-handler req)))

(defn -main []
  (println (str "Starting " service-name " on port " port))
  (println (str "OTLP endpoint: " otlp-endpoint))
  (println (str "Downstream services: " (pr-str downstream-urls)))
  (server/run-server handler {:port port})
  @(promise))

(-main)
