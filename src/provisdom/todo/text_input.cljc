(ns provisdom.todo.text-input
  (:require [clojure.spec.alpha :as s]
            [rum.core :as rum]
            [hasch.core :as hasch]
            [provisdom.maali.rules #?(:clj :refer :cljs :refer-macros) [defrules defqueries defsession def-derive] :as rules]
            [provisdom.todo.common :as common]))

(s/def ::id any?)
(s/def ::value string?)
(s/def ::initial-value ::value)
(s/def ::persistent? boolean?)
(s/def ::committed? boolean?)
(def-derive ::Input ::common/Request (s/keys :req [::id ::initial-value]))
(derive ::Input ::common/Cancellable)
(def-derive ::InputValue ::common/Response (s/keys :req [::value]))
(def-derive ::InputResponse ::InputValue)

(def-derive ::InputRequest ::common/Request (s/keys :req [::Input]))
(def-derive ::CommitRequest ::InputRequest)
(def-derive ::ValueRequest ::InputRequest)
(def-derive ::ValueResponse ::common/Response (s/keys :req [::value]))

(def request->response
  {::CommitRequest ::common/Response
   ::ValueRequest ::ValueResponse})

;;; Rules
(defrules rules
  [::input!
   "Create an InputValue fact to hold the current value of the Input."
   [?input <- ::Input (= ?initial-value initial-value)]
   [::common/ResponseFunction (= ?response-fn response-fn)]
   =>
   (rules/insert-unconditional! ::InputValue (common/request {::common/Request ?input ::value ?initial-value}))]

  [::comittable-input!
   "If the value of the Input is not empty, it is legal to commit the value."
   [?input <- ::Input]
   [::InputValue (= ?input Request) (not-empty value)]
   [::common/ResponseFunction (= ?response-fn response-fn)]
   =>
   (rules/insert! ::CommitRequest (common/request {::Input ?input} ::common/Response ?response-fn))]

  [::value-request!
   "Request an new value for the Input."
   [?input-value <- ::InputValue (= ?input Request)]
   [?input <- ::Input]
   [::common/ResponseFunction (= ?response-fn response-fn)]
   =>
   (rules/insert! ::ValueRequest (common/request {::Input ?input} ::ValueResponse ?response-fn))]

  [::value-response!
   "Handle the response to ValueRequest, update InputValue."
   [?input <- ::Input]
   [?request <- ::ValueRequest (= ?input Input)]
   [::ValueResponse (= ?request Request) (= ?value value)]
   [?input-value <- ::InputValue (= ?input Request)]
   =>
   (rules/upsert! ::InputValue ?input-value assoc ::value ?value)]

  [::commit-response!
   "Handle response to CommitRequest, insert InputResponse to signal
    the value has been committed."
   [?input <- ::Input]
   [?request <- ::CommitRequest (= ?input Input)]
   [::common/Response (= ?request Request)]
   [?input-value <- ::InputValue (= ?value value) (= ?input Request)]
   =>
   (rules/insert! ::InputResponse {::common/Request ?input ::value ?value})])

;;; Queries
(defqueries request-queries
  [::commit-request [:?input] [?request <- ::CommitRequest (= ?input Input)]]
  [::value-request [:?input] [?request <- ::ValueRequest (= ?input Input)]])

(defqueries view-queries
  [::value [:?input] [::InputValue (= ?input Request) (= ?value value)]])

(def productions (clojure.set/union common/productions
                                    #{provisdom.todo.text-input/rules
                                      provisdom.todo.text-input/request-queries
                                      provisdom.todo.text-input/view-queries}))

;;; View
(defn input-id
  "The logical ID used in facts could be anything, including a Clojure map.
   Use this function to make a HTML-friendly id from the hash of whatever data
   is contained in id."
  [id]
  (if (string? id) id (hasch/uuid id)))

(rum/defc text-input
          [session input & attrs]
          (let [attrs-map (into {} (map vec (partition 2 attrs)))
                commit-request (common/query-one :?request session ::commit-request :?input input)
                value-request (common/query-one :?request session ::value-request :?input input)
                value (common/query-one :?value session ::value :?input input)]
            (when value
              [:input (merge attrs-map
                             {:type        "text"
                              :value       value
                              :id          (input-id (::id input))
                              :on-change   #(common/respond-to value-request {::value (-> % .-target .-value)})
                              :on-blur     #(when commit-request (common/respond-to commit-request))
                              :on-key-down #(let [key-code (.-keyCode %)]
                                              (cond
                                                (and text-input (= 27 key-code)) (common/cancel-request input)
                                                (and commit-request (= 13 key-code)) (common/respond-to commit-request)))})])))
