-- Browser methods come from the client's bundled Widget.lua.
function PrivateWarehouse_OnLoad()
    PrivateWarehouseBrowser:CreateWebView();
    PrivateWarehouse:Hide();
end

function PrivateWarehouse_Open()
    PrivateWarehouse:Show();
    -- Queue a native layout pass after Show. The market-specific resize hook
    -- replaces this XML seed with the current viewport and sizes its browser.
    PrivateWarehouse:SetRect(0, 0, 1280, 960);
    PrivateWarehouseBrowser:LoadUrlWithWebAuth(PRIVATE_CENTRAL_MARKET_URL);
end

function PrivateMenus_Register()
    SlashCmdList["PRIVATEWAREHOUSE"] = PrivateWarehouse_Open;
    SLASH_PRIVATEWAREHOUSE1 = "/privatewarehouse";
    for _, entry in ipairs(PRIVATE_SERVER_MENUS) do
        if entry.command == "warehouse" then
            RegisterMenu(entry.label, SLASH_PRIVATEWAREHOUSE1, "v5_start_menu_relic_up");
        else
            RegisterMenu(entry.label, "/say ." .. entry.command, "v5_start_menu_relic_up");
        end
    end
end
