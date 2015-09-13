#!/usr/bin/env escript
%% -*- erlang -*-
%%! -name bar@127.0.0.1

main(_) ->
    erlang:display("Connecting to spaz@..."),
    true = net_kernel:connect_node('spaz@127.0.0.1').
