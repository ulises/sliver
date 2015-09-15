#!/usr/bin/env escript
%% -*- erlang -*-
%%! -name foo@127.0.0.1 -setcookie monster

main(_) ->
    true = net_kernel:connect_node('bar@127.0.0.1'),

    Self = self(),
    Pid = spawn(fun() ->
        receive {From, Message} ->
            From ! Message,
            Self ! done
        end
    end),
    register(echo, Pid),

    receive
        _ -> ok
    end.
