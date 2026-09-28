-- Minimal ComputerCraft 1.63 environment for running the reactor program headless.
-- Java provides: __reactor (table of methods), __println(str), __simSeconds (number), __clicks (bool)

colours = {white=1, orange=2, magenta=4, lightBlue=8, yellow=16, lime=32, pink=64, grey=128, lightGrey=256,
           cyan=512, purple=1024, blue=2048, brown=4096, green=8192, red=16384, black=32768}
colors = colours
colours.gray = colours.grey; colours.lightGray = colours.lightGrey

-- term: 51x19 screen; drawing calls just check their arguments like CC does
local W, H = 51, 19
local function num(v, what) if type(v) ~= "number" then error("Expected number (" .. what .. ")", 3) end end
term = {
  getSize = function() return W, H end,
  setCursorPos = function(x, y) num(x, "x"); num(y, "y") end,
  write = function(t) end,
  clear = function() end,
  setBackgroundColour = function(c) num(c, "colour") end,
  setTextColour = function(c) num(c, "colour") end,
  isColour = function() return true end,
}
term.setBackgroundColor = term.setBackgroundColour
term.setTextColor = term.setTextColour
term.isColor = term.isColour

function print(...)
  local t = {}
  for i = 1, select("#", ...) do t[#t + 1] = tostring((select(i, ...))) end
  __println(table.concat(t, "\t"))
end

-- fs: in-memory files; config has auto-sleep off like the user's setup
local files = { config = "CanDisplayAutoSleep=false\nAutomation_IsEnabled=false\nAutomation_Minimum=0\nAutomation_Maximum=100\n" }
fs = {
  exists = function(p) return files[p] ~= nil end,
  open = function(path, mode)
    if mode == "r" then
      local data = files[path]
      if data == nil then return nil end
      local pos = 1
      return {
        readLine = function()
          if pos > #data then return nil end
          local e = data:find("\n", pos, true) or (#data + 1)
          local line = data:sub(pos, e - 1); pos = e + 1; return line
        end,
        readAll = function() local r = data:sub(pos); pos = #data + 1; return r end,
        close = function() end,
      }
    else
      local buf = {}
      return {
        write = function(s) buf[#buf + 1] = tostring(s) end,
        writeLine = function(s) buf[#buf + 1] = tostring(s) .. "\n" end,
        flush = function() end,
        close = function() files[path] = table.concat(buf) end,
      }
    end
  end,
}

-- peripheral: one reactor on the back, no monitor (program falls back to term)
peripheral = {
  getNames = function() if __monitor then return { "back", "right" } end return { "back" } end,
  getType = function(n) if n == "back" then return "BigReactors-Reactor" elseif n == "right" and __monitor then return "monitor" end end,
  wrap = function(n) if n == "back" then return __reactor elseif n == "right" then return __monitor end end,
  isPresent = function(n) return n == "back" or (n == "right" and __monitor ~= nil) end,
}

-- os: virtual clock, timers and an event queue
local clock = 0
local nextTimer = 0
local timers = {}   -- id -> fire time
local queue = {}
local nextClick = 30
os = {}
function os.clock() return clock end
function os.time() return (clock / 50) % 24 end
function os.startTimer(delay)
  nextTimer = nextTimer + 1
  timers[nextTimer] = clock + math.max(delay or 0, 0)
  return nextTimer
end
function os.queueEvent(...) queue[#queue + 1] = { ... } end
function os.pullEventRaw(filter)
  while true do
    if #queue > 0 then
      local ev = table.remove(queue, 1)
      return unpack(ev)
    end
    local bestId, bestT
    for id, t in pairs(timers) do
      if bestT == nil or t < bestT or (t == bestT and id < bestId) then bestId, bestT = id, t end
    end
    if bestT == nil then return "terminate" end
    if __clicks and nextClick <= bestT and nextClick < __simSeconds then
      -- a player clicking around: tabs along the top row, buttons elsewhere
      clock = math.max(clock, nextClick)
      local x = (math.floor(nextClick) * 7) % W + 1
      local y = ((math.floor(nextClick) * 3) % H) + 1
      nextClick = nextClick + 37
      return "mouse_click", 1, x, y
    end
    if bestT > __simSeconds then return "terminate" end
    timers[bestId] = nil
    clock = math.max(clock, bestT)
    return "timer", bestId
  end
end
os.pullEvent = os.pullEventRaw
function os.reboot()
  for k in pairs(timers) do timers[k] = nil end
  for i = #queue, 1, -1 do queue[i] = nil end
  error("__REBOOT__", 0)
end
function os.getComputerID() return 1 end
function sleep() end
