#!/usr/bin/env escript
%% -*- erlang -*-
%%! -sname foo -setcookie monster

main(_) ->
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
