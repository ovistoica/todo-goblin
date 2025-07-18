(ns todo-goblin.config-test
  "Tests for todo-goblin.config namespace"
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [todo-goblin.config :as config]))

(def test-config-dir "/tmp/todo-goblin-test")
(def test-config-file (str test-config-dir "/config.edn"))

(defn cleanup-test-config
  "Test fixture to clean up test config files"
  [f]
  (try
    (f)
    (finally
      (when (.exists (io/file test-config-file))
        (io/delete-file test-config-file))
      (when (.exists (io/file test-config-dir))
        (.delete (io/file test-config-dir))))))

(use-fixtures :each cleanup-test-config)

(deftest test-load-config-empty
  (testing "load-config returns empty map when file doesn't exist"
    (is (empty? (config/load-config test-config-file)))))

(deftest test-save-and-load-config
  (testing "save-config! and load-config work together"
    (let [test-config {:global {:worktree-base-path "/tmp/worktrees"}
                       :projects {"test-project" {:cwd "/tmp/test"
                                                  :github/repo "user/repo"
                                                  :cron "0 9 * * *"
                                                  :todo/type :org
                                                  :todo/file "todo.org"}}}]

      (config/save-config! test-config test-config-file)
      (is (.exists (io/file test-config-file)))

      (let [loaded-config (config/load-config test-config-file)]
        (is (= test-config loaded-config))))))

(deftest test-get-project-config
  (testing "get-project-config retrieves correct project"
    (let [test-config {:projects {"project1" {:cwd "/path1"}
                                  "project2" {:cwd "/path2"}}}]
      (is (= {:cwd "/path1"} (config/get-project-config test-config "project1")))
      (is (= {:cwd "/path2"} (config/get-project-config test-config "project2")))
      (is (nil? (config/get-project-config test-config "nonexistent"))))))

(deftest test-get-active-project-config
  (testing "get-active-project-config finds project by cwd"
    (let [test-config {:projects {"project1" {:cwd "/workspace/proj1"}
                                  "project2" {:cwd "/workspace/proj2"}}}]
      (is (= {:cwd "/workspace/proj1"}
             (config/get-active-project-config test-config "/workspace/proj1")))
      (is (= {:cwd "/workspace/proj2"}
             (config/get-active-project-config test-config "/workspace/proj2")))
      (is (nil? (config/get-active-project-config test-config "/nonexistent"))))))

(deftest test-add-project-config
  (testing "add-project-config! adds new project"
    (let [initial-config {:projects {"existing" {:cwd "/existing"}}}
          new-project-config {:cwd "/new" :github/repo "user/new"}
          updated-config (config/add-project-config! initial-config "new-project" new-project-config)]

      (is (= new-project-config (get-in updated-config [:projects "new-project"])))
      (is (= {:cwd "/existing"} (get-in updated-config [:projects "existing"]))))))

(deftest test-ensure-config-dir
  (testing "ensure-config-dir! creates directory if it doesn't exist"
    (let [test-dir "/tmp/test-config-dir"]
      (when (.exists (io/file test-dir))
        (.delete (io/file test-dir)))

      (is (not (.exists (io/file test-dir))))
      (config/ensure-config-dir! test-dir)
      (is (.exists (io/file test-dir)))

      ;; Cleanup
      (.delete (io/file test-dir)))))
