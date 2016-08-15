(ns manifail-test
  (:require [clojure.test :refer :all]
            [manifold
             [deferred :as d]
             [stream :as s]
             [executor :as ex]]
            [manifail :as sut])
  (:import [clojure.lang ExceptionInfo]
           [java.util.concurrent Executors]))

(defn- ->seq [s] (vec s))

(deftest fixed-retry-times
  (is (= 1 (count (->seq (sut/retries 1)))))
  (is (= 5 (count (->seq (sut/retries 5))))))

(deftest limited-times
  (is (= 0 (-> 5 sut/retries (sut/limit-retries 0) ->seq count)))
  (is (= 1 (-> 5 sut/retries (sut/limit-retries 1) ->seq count)))
  (is (= 5 (-> 5 sut/retries (sut/limit-retries 6) ->seq count))))

(deftest limited-duration
  (is (= 0 (-> 1 sut/retries (sut/limit-duration 0) ->seq count)))

  (let [s (sut/limit-duration (sut/retries 100) 5)
        cnt (atom 0)]
    (d/loop [s' s]
      (Thread/sleep 1)
      (-> (or (first s') (d/success-deferred ::done))
          (d/chain
            (fn [result]
              (swap! cnt inc)
              (when-not (= ::done result) (d/recur (rest s')))))))
    (is (>= 5 @cnt))))

(defn around [expected-ms took-ms]
  (<= (- expected-ms 1) took-ms (+ expected-ms 1)))

(defn between [lower-ms upper-ms took-ms]
  (<= (- lower-ms 1) took-ms (+ upper-ms 1)))

(defn- realized-seq [s]
  s)

(deftest delayed
  (testing "simple delay"
    (is (= [50] (realized-seq (sut/delay (sut/retries 1) 50)))))

  (testing "backoff factor"
    (is (= [10 20 40] (realized-seq (sut/delay (sut/retries 3) 10 {:backoff-factor 2.0})))))

  (testing "backoff factor and max delay"
    (is (= [10 20 20]
           (realized-seq (sut/delay (sut/retries 3) 10
                                    {:backoff-factor 2.0, :max-delay-ms 20})))))

  (testing "jitter-ms"
    (let [[a b c] (realized-seq
                    (sut/delay (sut/retries 3) 20 {:jitter-ms 5}))]
      (is (around 20 a))
      (is (between 15 25 b))
      (is (between 10 30 c))))

  (testing "jitter-factor"
    (let [[a b c] (realized-seq
                    (sut/delay (sut/retries 3) 20 {:jitter-factor 0.5}))]
      (is (around 20 a))
      (is (between 10 30 b))
      (is (between 5 45 c)))))

(defn- aborted? [r]
  (try @r
       false
       (catch manifail.Aborted e true)))

(defn- retries-exceeded? [r]
  (try @r
       false
       (catch manifail.RetriesExceeded e true)))

(deftest with-retries
  (let [executed (atom 0)]

    (testing "executes once"
      (is
        (= ::done
           @(sut/with-retries* (sut/retries 1)
              #(do (swap! executed inc) ::done))))
      (is (= 1 @executed)))

    (reset! executed 0)

    (testing "executes several times, retries using value"
      (is
        (retries-exceeded?
          (sut/with-retries* (sut/retries 2)
            #(do (swap! executed inc)
                 sut/retry))))
      (is (= 3 @executed)))

    (reset! executed 0)

    (testing "executes several times, retries using retry!"
      (is
        (retries-exceeded?
          (sut/with-retries* (sut/retries 2)
            #(do (swap! executed inc)
                 (sut/retry!)))))
      (is (= 3 @executed)))

    (reset! executed 0)

    (testing "executes several times, retries using generic exception"
      (is
        (retries-exceeded?
          (sut/with-retries* (sut/retries 2)
            #(do (swap! executed inc)
                 (throw (Exception. "BOOM!"))))))
      (is (= 3 @executed)))

    (reset! executed 0)

    (testing "aborts execution using return value"
      (is
        (aborted?
          (sut/with-retries* (sut/retries 3)
            #(do (swap! executed inc)
                 (if (= @executed 2)
                   sut/abort
                   sut/retry)))))
      (is (= 2 @executed)))

    (reset! executed 0)

    (testing "aborts execution using exception"
      (is
        (aborted?
          (sut/with-retries* (sut/retries 3)
            #(do (swap! executed inc)
                 (if (= @executed 2)
                   (sut/abort!)
                   (sut/retry!))))))
      (is (= 2 @executed)))

    (reset! executed 0)

    (testing "limits duration"
      (is
        (retries-exceeded?
          (sut/with-retries* (sut/limit-duration (sut/retries 3) 50)
            #(do (swap! executed inc)
                 (Thread/sleep 30)
                 (sut/retry!)))))
      (is (= 2 @executed)))

    (reset! executed 0)

    (testing "limits duration with delay"
      (is
        (retries-exceeded?
          (sut/with-retries* (sut/limit-duration (sut/delay (sut/retries 3) 20) 50)
            #(do (swap! executed inc)
                 (Thread/sleep 30)
                 (sut/retry!)))))
      (is (= 2 @executed)))))
