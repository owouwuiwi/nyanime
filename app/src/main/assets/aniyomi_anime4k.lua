-- Anime4K telemetry uses mpv's public native properties. No network or per-frame polling.
-- Keep this filename equal to Anime4KTelemetry.SCRIPT_NAME (mpv sanitizes client names).
local utils = require 'mp.utils'
local enabled = false
local token = ''
local sequence = 0

local function finite(value)
    return type(value) == 'number' and value == value and math.abs(value) < math.huge
end

local function counter(name)
    local value = mp.get_property_native(name)
    if finite(value) and value >= 0 and value <= 9007199254740991 and value % 1 == 0 then return value end
end

local function render_time(passes)
    if type(passes) ~= 'table' or #passes == 0 or #passes > 128 then return nil end
    local total = 0
    for _, pass in ipairs(passes) do
        if type(pass) ~= 'table' or not finite(pass.avg) or pass.avg < 0 or
            not finite(pass.count) or pass.count <= 0 then return nil end
        total = total + pass.avg
    end
    if total > 0 and total < 10000000000 then return total / 1000000 end
end

local function sample()
    if not enabled then return end
    sequence = sequence + 1
    local playing = not mp.get_property_native('pause', true) and
        not mp.get_property_native('paused-for-cache', false) and
        not mp.get_property_native('seeking', false) and
        not mp.get_property_native('idle-active', true) and
        not mp.get_property_native('eof-reached', false)
    local passes = playing and mp.get_property_native('vo-passes') or nil
    local data = {
        version = 1, token = token, sequence = sequence, playing = playing,
        outputDrops = counter('frame-drop-count'),
        decoderDrops = counter('decoder-frame-drop-count'),
        delayedFrames = counter('vo-delayed-frame-count'),
        mistimedFrames = counter('mistimed-frame-count'),
        renderTimeMillis = type(passes) == 'table' and render_time(passes.fresh) or nil,
        redrawTimeMillis = type(passes) == 'table' and render_time(passes.redraw) or nil,
    }
    mp.set_property('user-data/aniyomi-anime4k/telemetry', utils.format_json(data))
end

local timer = mp.add_periodic_timer(1, sample)
timer:kill()
mp.register_script_message('set-active', function(value, session)
    enabled = value == 'yes'
    token = session or ''
    if enabled then
        timer:resume()
    else
        timer:kill()
    end
end)
