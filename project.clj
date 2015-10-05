(defproject sliver "0.0.1-SNAPSHOT"
  :description "A library to ease the simulation of native Erlang nodes."

  :url "http://github.com/ulises/sliver"

  :license {:name         "MIT"
            :url          "http://opensource.org/licenses/MIT"
            :distribution :repo}

  :deploy-repositories [["releases" {:url "https://clojars.org/repo"
                                     :creds :gpg}]
                        ["snapshots" {:url "https://clojars.org/repo"
                                      :creds :gpg}]]

  :signing {:gpg-key "0E96E893"}

  :scm {:name "git"
        :url "https://github.com/ulises/sliver"}

  :dependencies [[org.clojure/clojure "1.6.0"]
                 [bytebuffer "0.2.0"]
                 [com.taoensso/timbre "3.3.1"]
                 [borges "0.1.5"]]

  :global-vars {*warn-on-reflection* true}
  :aot :all)
