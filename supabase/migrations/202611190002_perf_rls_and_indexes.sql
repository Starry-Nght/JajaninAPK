-- Performance optimization migration:
-- 1) Remove 5 duplicate indexes
-- 2) Add 6 covering indexes on unindexed foreign keys
-- 3) Replace per-row auth.<fn>() calls with (select auth.<fn>()) and consolidate
--    overlapping permissive RLS policies (preserving exact access semantics).

-- =========================================================
-- 1. Duplicate indexes cleanup
-- =========================================================
drop index if exists public.menu_items_stall_id_idx;
drop index if exists public.order_items_menu_item_idx;
drop index if exists public.order_items_order_id_idx;
drop index if exists public.orders_created_at_idx;
drop index if exists public.orders_status_idx;

-- =========================================================
-- 2. Missing foreign-key covering indexes
-- =========================================================
create index if not exists idx_app_releases_created_by on public.app_releases (created_by);
create index if not exists idx_chat_messages_sender_id on public.chat_messages (sender_id);
create index if not exists idx_developer_announcements_created_by on public.developer_announcements (created_by);
create index if not exists idx_developer_warnings_created_by on public.developer_warnings (created_by);
create index if not exists idx_orders_verified_by on public.orders (verified_by);
create index if not exists idx_seller_invites_created_by on public.seller_invites (created_by);

-- =========================================================
-- 3. Consolidated & initplan-cached RLS policies
-- =========================================================

-- profiles
drop policy if exists "Admins can read all profiles" on public.profiles;
drop policy if exists "Developers read all profiles" on public.profiles;
drop policy if exists "Profiles are readable by their owner" on public.profiles;
drop policy if exists "Users can create their profile" on public.profiles;
drop policy if exists "Developers manage restrictions" on public.profiles;
drop policy if exists "Users can update their profile" on public.profiles;

create policy "Profiles select policy"
    on public.profiles for select to authenticated
    using (
        id = (select auth.uid())
        or (select public.is_admin())
        or (select public.is_developer())
    );

create policy "Profiles insert policy"
    on public.profiles for insert to authenticated
    with check (id = (select auth.uid()));

create policy "Profiles update policy"
    on public.profiles for update to authenticated
    using (id = (select auth.uid()) or (select public.is_developer()))
    with check (id = (select auth.uid()) or (select public.is_developer()));

-- stalls
drop policy if exists "Open or seller-owned stalls are readable" on public.stalls;
drop policy if exists "Developers read all stalls" on public.stalls;
drop policy if exists "seller_view_own_stalls" on public.stalls;
drop policy if exists "Sellers create their stalls" on public.stalls;
drop policy if exists "seller_insert_stall" on public.stalls;
drop policy if exists "Developers toggle stalls" on public.stalls;
drop policy if exists "Sellers update their stalls" on public.stalls;
drop policy if exists "seller_update_own_stall" on public.stalls;
drop policy if exists "seller_delete_own_stall" on public.stalls;

create policy "Stalls select policy"
    on public.stalls for select to anon, authenticated
    using (
        is_open
        or owner_id = (select auth.uid())
        or (select public.is_developer())
    );

create policy "Stalls insert policy"
    on public.stalls for insert to authenticated
    with check (owner_id = (select auth.uid()));

create policy "Stalls update policy"
    on public.stalls for update to authenticated
    using (owner_id = (select auth.uid()) or (select public.is_developer()))
    with check (owner_id = (select auth.uid()) or (select public.is_developer()));

create policy "Stalls delete policy"
    on public.stalls for delete to authenticated
    using (owner_id = (select auth.uid()));

-- menu_items
drop policy if exists "Available menu items are public" on public.menu_items;
drop policy if exists "Developers read all menu items" on public.menu_items;
drop policy if exists "Sellers manage their menu items" on public.menu_items;
drop policy if exists "seller_view_own_menu" on public.menu_items;
drop policy if exists "seller_insert_menu" on public.menu_items;
drop policy if exists "seller_update_menu" on public.menu_items;
drop policy if exists "seller_delete_menu" on public.menu_items;

create policy "Menu items select policy"
    on public.menu_items for select to anon, authenticated
    using (
        is_available
        or (select public.is_developer())
        or exists (
            select 1 from public.stalls s
            where s.id = menu_items.stall_id and s.owner_id = (select auth.uid())
        )
    );

create policy "Menu items insert policy"
    on public.menu_items for insert to authenticated
    with check (
        exists (
            select 1 from public.stalls s
            where s.id = menu_items.stall_id and s.owner_id = (select auth.uid())
        )
    );

create policy "Menu items update policy"
    on public.menu_items for update to authenticated
    using (
        exists (
            select 1 from public.stalls s
            where s.id = menu_items.stall_id and s.owner_id = (select auth.uid())
        )
    )
    with check (
        exists (
            select 1 from public.stalls s
            where s.id = menu_items.stall_id and s.owner_id = (select auth.uid())
        )
    );

create policy "Menu items delete policy"
    on public.menu_items for delete to authenticated
    using (
        exists (
            select 1 from public.stalls s
            where s.id = menu_items.stall_id and s.owner_id = (select auth.uid())
        )
    );

-- orders
drop policy if exists "Developers read all orders" on public.orders;
drop policy if exists "Users can read their orders" on public.orders;
drop policy if exists "seller_view_orders" on public.orders;
drop policy if exists "Users can create their orders" on public.orders;
drop policy if exists "Buyers request payment verification" on public.orders;
drop policy if exists "seller_update_orders" on public.orders;
drop policy if exists "Owners can delete their pending orders" on public.orders;
drop policy if exists "Sellers can delete stall orders" on public.orders;

create policy "Orders select policy"
    on public.orders for select to authenticated
    using (
        user_id = (select auth.uid())
        or (select public.is_developer())
        or public.is_order_for_seller(id)
    );

create policy "Orders insert policy"
    on public.orders for insert to authenticated
    with check (user_id = (select auth.uid()));

create policy "Orders update policy"
    on public.orders for update to authenticated
    using (user_id = (select auth.uid()) or public.is_order_for_seller(id))
    with check (user_id = (select auth.uid()) or public.is_order_for_seller(id));

create policy "Orders delete policy"
    on public.orders for delete to authenticated
    using (
        (select public.is_developer())
        or (user_id = (select auth.uid()) and status = 'pending')
        or (
            (select public.is_seller())
            and (
                public.is_order_for_seller(id)
                or verified_by = (select auth.uid())
                or not exists (select 1 from public.order_items oi where oi.order_id = orders.id)
                or exists (select 1 from public.stalls s where s.owner_id = (select auth.uid()))
            )
        )
    );

-- order_items
drop policy if exists "Developers read all order items" on public.order_items;
drop policy if exists "Users can read items in their orders" on public.order_items;
drop policy if exists "seller_view_order_items" on public.order_items;
drop policy if exists "Users can add items to their orders" on public.order_items;
drop policy if exists "Owners can delete items in their pending orders" on public.order_items;
drop policy if exists "Sellers can delete order items" on public.order_items;

create policy "Order items select policy"
    on public.order_items for select to authenticated
    using (
        (select public.is_developer())
        or exists (
            select 1 from public.orders o
            where o.id = order_items.order_id and o.user_id = (select auth.uid())
        )
        or exists (
            select 1 from public.menu_items mi
            join public.stalls s on s.id = mi.stall_id
            where mi.id = order_items.menu_item_id and s.owner_id = (select auth.uid())
        )
    );

create policy "Order items insert policy"
    on public.order_items for insert to authenticated
    with check (
        exists (
            select 1 from public.orders o
            where o.id = order_items.order_id and o.user_id = (select auth.uid())
        )
    );

create policy "Order items delete policy"
    on public.order_items for delete to authenticated
    using (
        (select public.is_developer())
        or exists (
            select 1 from public.orders o
            where o.id = order_items.order_id and o.user_id = (select auth.uid()) and o.status = 'pending'
        )
        or (
            (select public.is_seller())
            and exists (
                select 1 from public.orders o
                where o.id = order_items.order_id
                  and exists (select 1 from public.stalls s where s.owner_id = (select auth.uid()))
            )
        )
    );

-- admin_devices
drop policy if exists "Owners manage own device" on public.admin_devices;
drop policy if exists "seller_view_own_devices" on public.admin_devices;
drop policy if exists "seller_insert_own_device" on public.admin_devices;
drop policy if exists "seller_update_own_device" on public.admin_devices;
drop policy if exists "seller_delete_own_device" on public.admin_devices;

create policy "Admin devices owner policy"
    on public.admin_devices for all to authenticated
    using (user_id = (select auth.uid()))
    with check (user_id = (select auth.uid()));

-- profile_phones
drop policy if exists "Owners manage own phone" on public.profile_phones;
drop policy if exists "Developers read all phones" on public.profile_phones;
drop policy if exists "Sellers read linked buyer phones" on public.profile_phones;

create policy "Profile phones select policy"
    on public.profile_phones for select to authenticated
    using (
        user_id = (select auth.uid())
        or (select public.is_developer())
        or exists (
            select 1 from public.orders o
            join public.order_items oi on oi.order_id = o.id
            join public.menu_items mi on mi.id = oi.menu_item_id
            join public.stalls s on s.id = mi.stall_id
            where o.user_id = profile_phones.user_id and s.owner_id = (select auth.uid())
        )
    );

create policy "Profile phones insert policy"
    on public.profile_phones for insert to authenticated
    with check (user_id = (select auth.uid()));

create policy "Profile phones update policy"
    on public.profile_phones for update to authenticated
    using (user_id = (select auth.uid()))
    with check (user_id = (select auth.uid()));

create policy "Profile phones delete policy"
    on public.profile_phones for delete to authenticated
    using (user_id = (select auth.uid()));

-- developer_warnings
drop policy if exists "Developers manage warnings" on public.developer_warnings;
drop policy if exists "Developers delete warnings" on public.developer_warnings;
drop policy if exists "Users read own warnings" on public.developer_warnings;
drop policy if exists "Users mark own warnings read" on public.developer_warnings;

create policy "Warnings select policy"
    on public.developer_warnings for select to authenticated
    using (target_user_id = (select auth.uid()) or (select public.is_developer()));

create policy "Warnings insert policy"
    on public.developer_warnings for insert to authenticated
    with check ((select public.is_developer()));

create policy "Warnings update policy"
    on public.developer_warnings for update to authenticated
    using (target_user_id = (select auth.uid()) or (select public.is_developer()))
    with check (target_user_id = (select auth.uid()) or (select public.is_developer()));

create policy "Warnings delete policy"
    on public.developer_warnings for delete to authenticated
    using ((select public.is_developer()));

-- developer_announcements
drop policy if exists "Developers manage announcements" on public.developer_announcements;
drop policy if exists "Developers delete announcements" on public.developer_announcements;
drop policy if exists "Users read announcements" on public.developer_announcements;

create policy "Announcements select policy"
    on public.developer_announcements for select to authenticated
    using (
        (select public.is_developer())
        or (
            is_active
            and (expires_at is null or expires_at > now())
            and (
                target_role = 'all'
                or (target_role = 'buyers' and (select public.my_role()) = 'buyer')
                or (target_role = 'sellers' and (select public.my_role()) = 'seller')
            )
        )
    );

create policy "Announcements insert policy"
    on public.developer_announcements for insert to authenticated
    with check ((select public.is_developer()));

create policy "Announcements update policy"
    on public.developer_announcements for update to authenticated
    using ((select public.is_developer()))
    with check ((select public.is_developer()));

create policy "Announcements delete policy"
    on public.developer_announcements for delete to authenticated
    using ((select public.is_developer()));

-- seller_invites
drop policy if exists "Developers manage invites" on public.seller_invites;
drop policy if exists "Users read own invites" on public.seller_invites;

create policy "Seller invites select policy"
    on public.seller_invites for select to authenticated
    using (target_user_id = (select auth.uid()) or (select public.is_developer()));

create policy "Seller invites insert policy"
    on public.seller_invites for insert to authenticated
    with check ((select public.is_developer()));

create policy "Seller invites update policy"
    on public.seller_invites for update to authenticated
    using ((select public.is_developer()))
    with check ((select public.is_developer()));

create policy "Seller invites delete policy"
    on public.seller_invites for delete to authenticated
    using ((select public.is_developer()));

-- conversations & chat_messages
drop policy if exists "Members read own conversations" on public.conversations;
create policy "Members read own conversations"
    on public.conversations for select to authenticated
    using ((select auth.uid()) = buyer_id or (select auth.uid()) = seller_id);

drop policy if exists "Members read own messages" on public.chat_messages;
create policy "Members read own messages"
    on public.chat_messages for select to authenticated
    using (
        exists (
            select 1 from public.conversations c
            where c.id = chat_messages.conversation_id
              and (c.buyer_id = (select auth.uid()) or c.seller_id = (select auth.uid()))
        )
    );
