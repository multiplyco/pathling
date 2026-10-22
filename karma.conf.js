module.exports = function (config) {
    config.set({
        // Ignore application PORT/IP/LISTEN_ADDR settings inherited from the shell.
        port: 9876,
        hostname: '127.0.0.1',
        listenAddress: '127.0.0.1',
        browsers: ['ChromeHeadless'],
        basePath: 'target',
        files: [process.env.PATHLING_CLJS_TEST_FILE || 'ci.js'],
        frameworks: ['cljs-test'],
        plugins: ['karma-cljs-test', 'karma-chrome-launcher'],
        colors: true,
        logLevel: config.LOG_INFO,
        client: {
            args: ["shadow.test.karma.init"],
            singleRun: true
        }
    })
};
