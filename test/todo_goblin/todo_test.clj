(ns todo-goblin.todo-test
  "Tests for todo-goblin.todo namespace"
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [todo-goblin.specs :as specs]
            [todo-goblin.todo :as todo]))

(def test-org-content
  "* TODO Implement user authentication
This task involves adding JWT authentication to the API.

** Requirements
- Add JWT middleware
- Create login/logout endpoints
- Add user session management

* DONE Fix bug in payment processing
This was already completed last week.

* TODO Add database migrations
We need to set up proper database versioning.

** Details
- Use Flyway for migrations
- Create initial schema
- Add migration scripts

* IN-PROGRESS Refactor core namespace
Currently working on this task.
")

(def test-org-file "/tmp/test-todo.org")

(defn setup-test-org-file [f]
  "Test fixture to create and clean up test org file"
  (try
    (spit test-org-file test-org-content)
    (f)
    (finally
      (when (.exists (io/file test-org-file))
        (io/delete-file test-org-file)))))

(use-fixtures :each setup-test-org-file)

(deftest test-parse-org-line
  (testing "parse-org-line correctly parses TODO lines"
    (is (= {:status :todo :title "Implement feature X" :line-num 0}
           (todo/parse-org-line "* TODO Implement feature X" 0)))

    (is (= {:status :done :title "Fix bug Y" :line-num 1}
           (todo/parse-org-line "** DONE Fix bug Y" 1)))

    (is (= {:status :in-progress :title "Refactor module Z" :line-num 2}
           (todo/parse-org-line "*** IN-PROGRESS Refactor module Z" 2)))

    (is (nil? (todo/parse-org-line "Regular text line" 0)))
    (is (nil? (todo/parse-org-line "* Regular heading" 0)))))

(deftest test-read-org-file
  (testing "read-org-file parses org file correctly"
    (let [tasks (todo/read-org-file test-org-file "test-project")]

      (is (= 2 (count tasks))) ; Only TODO items, not DONE or IN-PROGRESS

      (let [first-task (first tasks)]
        (is (= "Implement user authentication" (:title first-task)))
        (is (= :todo (:status first-task)))
        (is (= :org (:source first-task)))
        (is (= "test-project" (:project-name first-task)))
        (is (some? (:id first-task)))
        (is (some? (:description first-task))))

      (let [second-task (second tasks)]
        (is (= "Add database migrations" (:title second-task)))
        (is (= :todo (:status second-task)))))))

(deftest test-read-org-file-nonexistent
  (testing "read-org-file returns empty list for nonexistent file"
    (is (empty? (todo/read-org-file "/nonexistent/file.org" "test-project")))))

(deftest test-generate-task-branch-name
  (testing "generate-task-branch-name creates valid git branch names"
    (let [task {:title "Fix authentication bug in login module"
                :id "task-123456789"}
          branch-name (todo/generate-task-branch-name task)]
      (is (= "tgbl/fix-authentication-bug-in-logi-task-123" branch-name))
      (is (< (count branch-name) 50)) ; Reasonable length limit
      (is (re-matches #"tgbl/[a-z0-9-]+" branch-name)))))

(deftest test-generate-task-branch-name-special-chars
  (testing "generate-task-branch-name handles special characters"
    (let [task {:title "Fix API endpoint: /users/{id} returns 500!"
                :id "task-abc123"}
          branch-name (todo/generate-task-branch-name task)]
      (is (re-matches #"tgbl/[a-z0-9-]+" branch-name))
      (is (not (re-find #"[^a-z0-9/-]" branch-name))))))

(deftest test-get-tasks
  (testing "get-tasks dispatches correctly based on todo/type"
    (let [org-config {:todo/type :org
                      :cwd "/tmp"
                      :todo/file "test-todo.org"}
          github-config {:todo/type :github
                         :github/repo "user/repo"
                         :github/issues-label "todo"}
          org-tasks (todo/get-tasks org-config "test-project")]

      ;; Test org type (actual parsing)
      (is (seq org-tasks))
      (is (every? #(= :org (:source %)) org-tasks))

      ;; Test github type returns empty list (since we're not mocking gh CLI)
      (let [github-tasks (todo/get-tasks github-config "test-project")]
        (is (coll? github-tasks))))))

(deftest test-task-summary
  (testing "task-summary generates readable summary"
    (let [task {:title "Test task"
                :id "test-123"
                :source :org
                :status :todo
                :description "Test description"}
          summary (todo/task-summary task)]

      (is (string? summary))
      (is (.contains summary "Test task"))
      (is (.contains summary "test-123"))
      (is (.contains summary "org"))
      (is (.contains summary "todo"))
      (is (.contains summary "Test description")))))

(deftest test-filter-available-tasks
  (testing "filter-available-tasks excludes tasks with existing PRs/worktrees"
    (let [tasks [{:id "task-1" :title "Task 1"}
                 {:id "task-2" :title "Task 2"}
                 {:id "task-3" :title "Task 3"}]
          existing-prs [{:task-id "task-1"}]
          existing-worktrees [{:task-id "task-2"}]
          available (todo/filter-available-tasks tasks existing-prs existing-worktrees)]

      (is (= 1 (count available)))
      (is (= "task-3" (:id (first available)))))))

(deftest test-select-task
  (testing "select-task returns first available task"
    (let [tasks [{:id "task-1" :title "First task"}
                 {:id "task-2" :title "Second task"}]]
      (is (= "task-1" (:id (todo/select-task tasks))))
      (is (nil? (todo/select-task []))))))
