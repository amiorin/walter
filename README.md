## Intro
Walter is the first BigConfig package. It creates a remote development environment in Hetzner Cloud. It requires an Hetzner token.

``` sh
# Install babashka, opentofu, and ansible
# Or use https://devenv.sh
brew install --cask temurin@21
brew install clojure/tools/clojure
brew install borkdude/brew/babashka
brew install opentofu
brew install ansible

# tool workflow
bb tofu|ansible <step>+

# composition workflow
bb walter create|delete
```

## Links
* https://bigconfig.it/
* https://bigconfig.it/blog/introducing-bigconfig-package/
* https://bigconfig.it/api/package/
* https://babashka.org/
* https://www.hetzner.com/
