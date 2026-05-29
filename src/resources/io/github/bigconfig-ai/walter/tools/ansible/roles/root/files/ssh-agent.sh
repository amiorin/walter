if [[ -n "$SSH_AUTH_SOCK" ]]; then
    target="/tmp/$(whoami)@$(hostname).agent"
    if [[ "$SSH_AUTH_SOCK" != "$target" ]]; then
        ln -sf "$SSH_AUTH_SOCK" "$target"
    fi
fi
