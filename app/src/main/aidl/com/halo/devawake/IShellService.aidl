package com.halo.devawake;

interface IShellService {
    // Shizuku 销毁 UserService 时会按这个固定事务号调用 destroy()
    void destroy() = 16777114;

    String exec(String command) = 1;
}
