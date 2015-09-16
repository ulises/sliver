#!/usr/bin/env escript
%% -*- erlang -*-
%%! -name foo@127.0.0.1 -setcookie random

main(_) ->
    erlang:display("Connecting to bar@..."),
    false = net_kernel:connect_node('bar@127.0.0.1').
