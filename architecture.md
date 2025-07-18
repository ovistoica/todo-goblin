# Todo-Goblin Architecture

The "todo-goblin" architecture will prioritize functional programming, immutability, and a clear separation of concerns, leveraging Babashka for CLI operations and `gh` CLI for GitHub interactions.

## 1. Overall Architecture and Namespace Organization

The project will be structured into several distinct namespaces, each responsible for a specific functional area. This promotes modularity, testability, and maintainability.

* `todo-goblin.core`: The main entry point for the CLI. Handles argument parsing, command dispatch, and orchestrates calls to other namespaces.
* `todo-goblin.config`: Manages reading, writing, initializing, and displaying configuration settings.
* `todo-goblin.github`: Provides functions for interacting with the `gh` command-line tool for GitHub API operations (PRs, issues, authentication).
* `todo-goblin.todo`: Contains logic for parsing various TODO sources (Org files, GitHub issues) into a standardized internal task representation.
* `todo-goblin.worktree`: Manages Git worktree operations, ensuring isolated development environments for AI agents.
* `todo-goblin.ai`: Abstracts the interaction with the Claude Code CLI, providing a clean interface for AI task execution.
* `todo-goblin.agent`: The core orchestration logic for selecting, processing, and managing tasks, including worktree setup, AI invocation, and PR updates. This namespace will contain the primary "agent loop" logic.
* `todo-goblin.util`: General utility functions and helper macros.

## 2. Data-Centric Design and Core Data Structures

A consistent data representation is crucial for a data-centric design. All internal operations will transform raw inputs into well-defined Clojure maps.

### Configuration (`:todo-goblin/config`)

A nested map structure, loaded from `~/.config/todo-goblin/config.edn`.

```clojure
{:global
 {:worktree-base-path "~/.todo-goblin/worktrees"}
 :projects
 {"project-name-1" {:cwd "/path/to/project-1"
                    :github/repo "user/repo-1"
                    :cron "* * * *" ; Note: Cron is external trigger, not managed by tgbl
                    :todo/type :org ; or :github
                    :todo/file "todo.org" ; or nil for :github
                    :github/issues-label "todo" ; for :github type
                    }
            "project-name-2" {;; ... other project config ...
            }}}
```

### Task (`:todo-goblin/task`)

A standardized map representing a single task, regardless of its source (Org file, GitHub issue).

```clojure
{:id             "unique-task-id" ; UUID or derived from source
 :title          "Task Title"
 :description    "Detailed description of the task."
 :source         :org             ; or :github
 :source-id      "original-source-identifier" ; e.g., org file heading path, GitHub issue number
 :status         :todo            ; or :in-progress, :done
 :assigned-pr    {:pr/number 123 :pr/branch "feature/task-branch"} ; if associated with a PR
 :project-name   "project-name-key" ; Link back to config project
 :worktree-path  "/path/to/worktree" ; Current worktree path if in-progress
 }
```

### Pull Request (`:todo-goblin/pull-request`)

A standardized map representing a GitHub Pull Request.

```clojure
{:pr/number      123
 :pr/title       "WIP: Implement feature X"
 :pr/state       :open ; or :closed, :merged
 :pr/is-draft    true
 :pr/branch      "feature/task-branch"
 :pr/url         "https://github.com/user/repo/pull/123"
 :project-name   "project-name-key"
 }
```

## 3. Implementation Plan by Namespace

### `todo-goblin.core`

* **Responsibility**: CLI command parsing and dispatch.
* **Approach**:
  * Use `babashka.cli` for argument parsing.
  * Define a main function that dispatches to specific handler functions based on the parsed command and sub-command.
  * Example dispatch: `(cli/dispatch {:config {:init config/init :show config/show} :agent {:run agent/run} :status status/show})`
* **Clojure Idioms**: Use `cond` for command dispatching, `assoc-in` for deep merging config.

### `todo-goblin.config`

* **Responsibility**: Managing the `config.edn` file.
* **Functions**:
  * `init-config`:
    * Interactively prompt the user for project details (project name, CWD, GitHub repo, todo type, todo file/label).
    * Use `clojure.edn/read-string` and `clojure.string/trim` for input.
    * Construct the initial config map.
    * Write the config map to `~/.config/todo-goblin/config.edn` using `pr-str` and `spit`.
    * Use `io/file` to ensure config directory exists.
  * `load-config`:
    * Read `~/.config/todo-goblin/config.edn` using `slurp` and `clojure.edn/read-string`.
    * Return the parsed configuration map. Handle file not found by returning an empty map or throwing a specific error.
  * `get-project-config [config project-name]`: Retrieve a specific project's configuration from the loaded config map.
  * `get-active-project-config [config cwd]`: Determine the active project based on the current working directory and retrieve its configuration.
  * `show-config [config]`: Pretty-print the given configuration map using `pprint/pprint`.
* **Clojure Idioms**: Pure functions for map manipulation, `when` for conditional file creation.

### `todo-goblin.github`

* **Responsibility**: Wrapper for `gh` CLI commands.
* **Approach**: All functions will use `babashka.process/sh` to execute `gh` commands. Parse JSON output from `gh` using `cheshire.core/parse-string`.
* **Functions**:
  * `check-auth []`: Executes `gh auth status`. Returns boolean.
  * `list-open-prs [repo]`: Executes `gh pr list --json number,title,state,isDraft,headRefName`. Transforms output into a list of `:todo-goblin/pull-request` maps.
  * `create-draft-pr [repo branch title body]`: Executes `gh pr create --draft --base main --head <branch> --title <title> --body <body>`. Returns the created PR number and URL.
  * `update-pr-title [repo pr-number new-title]`: Executes `gh pr edit <pr-number> --title <new-title>`.
  * `list-issues [repo label]`: Executes `gh issue list --json number,title,body,labels --label <label>`. Transforms output into a list of `:todo-goblin/task` maps with `:source :github`.
* **Clojure Idioms**: Threading macros for processing `gh` output. Error handling with `try-catch` around `sh` calls.

### `todo-goblin.todo`

* **Responsibility**: Parsing various TODO sources.
* **Functions**:
  * `read-org-file [file-path project-name]`:
    * Reads content of the Org file.
    * Parses Org-mode headings and tasks (e.g., using regex or a simple parser) into a list of `:todo-goblin/task` maps.
    * Tasks should be identified by unique IDs (e.g., a hash of their content or a sequential number within the file).
    * Filter tasks by `TODO` status.
  * `get-tasks [project-config]`: A dispatcher function that calls `read-org-file` or `github/list-issues` based on `:todo/type` in `project-config`. Returns a list of `:todo-goblin/task` maps.
* **Clojure Idioms**: Pure functions for parsing. Pattern matching for different `:todo/type`.

### `todo-goblin.worktree`

* **Responsibility**: Managing Git worktrees.
* **Approach**: Uses `babashka.process/sh` for `git worktree` commands.
* **Functions**:
  * `get-worktree-path [base-path project-name task-id]`: Constructs a unique path for a worktree.
  * `create-worktree [repo-path worktree-path branch-name]`:
    * Executes `git -C <repo-path> worktree add <worktree-path> <branch-name>`.
    * Ensure `branch-name` is unique (e.g., derived from task title).
    * Returns the full worktree path.
  * `remove-worktree [worktree-path]`: Executes `git worktree remove <worktree-path>`.
  * `list-active-worktrees [base-path]`: Executes `git worktree list --porcelain` and parses output to find worktrees managed by todo-goblin. Returns a list of maps with `:path` and `:branch`.
* **Clojure Idioms**: `str/join` for path construction, `sh` for external command execution.

### `todo-goblin.ai`

* **Responsibility**: Interfacing with Claude Code CLI.
* **Functions**:
  * `run-claude-code [worktree-path task-description context-files]`:
    * Takes the `worktree-path` (where the AI will operate).
    * Constructs the prompt for Claude Code CLI using `task-description` and potentially content from `context-files`.
    * Executes `claude-code --model claude-3-opus <prompt>` (or similar) from within the `worktree-path`.
    * Redirects stdin/stdout as needed.
    * Returns the output of the AI (or `true`/`false` for success).
* **Clojure Idioms**: `sh` for execution, string templating for prompt construction.

### `todo-goblin.agent`

* **Responsibility**: Orchestrating the automated task completion. This is the heart of the "cron job" execution.
* **Functions**:
  * `select-task [project-config active-prs active-worktrees available-tasks]`:
    * Filters `available-tasks` to exclude those with existing draft PRs (`active-prs`) or active worktrees (`active-worktrees`).
    * Implements task selection heuristic (e.g., first available, simple heuristic).
    * Returns the selected `:todo-goblin/task` map or `nil`.
  * `process-task [task project-config]`:
    * Takes a selected `task`.
    * Derives a unique branch name (e.g., `feature/tgbl-<task-id>`).
    * Calls `worktree/create-worktree`.
    * Calls `github/create-draft-pr` for the new branch.
    * Updates the `task` map with `:assigned-pr` and `:worktree-path`.
    * Calls `ai/run-claude-code` within the worktree.
    * After AI completion, determines task status (e.g., by checking if new files/changes exist).
    * Commits AI changes within the worktree.
    * Pushes the branch from the worktree.
    * If task is "completed" by AI, calls `github/update-pr-title` (e.g., from "WIP: Task" to "FEAT: Task").
    * Potentially updates the original TODO source (e.g., mark Org task DONE, close GitHub issue).
    * Returns updated `task` map.
  * `run [project-name]`: The main agent loop function.
    * Loads global and project-specific configuration.
    * Calls `github/check-auth`.
    * Calls `github/list-open-prs`.
    * Calls `todo/get-tasks`.
    * Calls `worktree/list-active-worktrees`.
    * Calls `select-task` to pick a task.
    * If a task is selected, calls `process-task`.
    * Handles logging and error reporting throughout the process.
* **Clojure Idioms**: Heavy use of threading macros for orchestration, `if-let` for conditional processing, `do` for sequential actions.

### CLI Commands Implementation

* `tgbl config init`: Dispatches to `todo-goblin.config/init-config`.
* `tgbl config show`: Dispatches to `todo-goblin.config/show-config` after loading the config.
* `tgbl status`:
  * Loads configuration.
  * Determines active project based on CWD.
  * Calls `todo-goblin.worktree/list-active-worktrees` to show active AI agent worktrees.
  * Calls `todo-goblin.github/list-open-prs` and filters for draft PRs potentially created by `todo-goblin`.
  * Presents a summary of in-progress tasks and associated PRs.

## 4. Testing Strategy

* **Significant Other File Pattern**: For every `src/foo/bar.clj`, there will be a corresponding `test/foo/bar_test.clj`.
* **Unit Tests**:
  * Utilize `clojure.test` for all tests.
  * Focus on pure functions first: `todo-goblin.config` (map manipulations), `todo-goblin.todo` (parsing logic), `todo-goblin.util`.
  * Use `with-redefs` to mock `babashka.process/sh` calls when testing functions that interact with external CLIs (`gh`, `git`, `claude-code`). This is critical for fast, reliable, and isolated tests. Mocked `sh` calls should return predefined outputs (JSON, success codes) to simulate different scenarios.
  * Test edge cases: empty config, no tasks, existing conflicts, CLI failures.
* **Integration Tests**:
  * A limited set of integration tests can be written for the `todo-goblin.agent` namespace to ensure the orchestration logic flows correctly, but these will still rely heavily on mocked external CLI calls.
  * Consider setting up temporary directories and dummy Git repositories for testing worktree creation/removal without affecting the actual system.
* **REPL-Driven Development**: Encourage granular development and testing in the REPL for all functions before integrating them into larger flows. This allows for quick iteration and verification of small units of code.

This architecture provides a clear path for development, emphasizing Clojure's strengths in data manipulation, functional purity, and modular design.