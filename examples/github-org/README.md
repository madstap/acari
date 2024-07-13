# Github org, an example script

## Usage

From this directory, run the following commands

```shell
# Assuming that ~/.local/bin is on the PATH
ln -s "$(pwd)/github_org.cljc" ~/.local/bin/

# Install the completions for the current session
source <(github_org.cljc completions-script bash)

# Set the debug file
export COMP_DEBUG_FILE=dbg.txt
```

Now we will get completions.

```shell
github_org.clj <tab><tab>
```

Run this in a separate terminal to see what the script prints.

``` shell
tail -f dbg.txt
```
